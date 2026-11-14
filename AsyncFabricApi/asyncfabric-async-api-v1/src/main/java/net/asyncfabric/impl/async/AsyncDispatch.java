/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.asyncfabric.impl.async;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import net.asyncfabric.api.async.v1.AsyncScheduler;

/**
 * The two dispatch strategies, in one place.
 *
 * <p>Both strategies start every listener <em>on a worker thread</em>. That is the whole
 * point of the async bus: calling {@code call.apply(listener)} on the caller would run the
 * listener body on the game thread, so the work would never leave it. Every dispatch path
 * goes through this class for that reason — an earlier version inlined the fan-out and ran
 * listeners on the game thread.
 *
 * <p>Kept generic and free of Minecraft types so it can be unit tested directly.
 */
public final class AsyncDispatch {
	private static final Map<String, Gate> GATES = new ConcurrentHashMap<>();

	private AsyncDispatch() {
	}

	/**
	 * Runs all listeners concurrently, each on a worker, and joins them.
	 *
	 * <p>Listeners do not observe each other. A failing listener does not stop the others.
	 *
	 * @param name diagnostic name, used for failure counting and tick gating
	 * @param listeners the listeners to run, may be empty
	 * @param call starts one listener
	 * @param <T> listener type
	 * @return a future completing when every listener finished
	 */
	public static <T> CompletableFuture<Void> parallel(String name, T[] listeners, Function<T, CompletableFuture<Void>> call) {
		if (listeners.length == 0) {
			return CompletableFuture.completedFuture(null);
		}

		List<CompletableFuture<?>> futures = new ArrayList<>(listeners.length);

		for (T listener : listeners) {
			futures.add(AsyncScheduler.supplyAsync(() -> call.apply(listener))
					.thenCompose(Function.identity())
					.exceptionally(throwable -> {
						ListenerFailures.record(name, throwable);
						return null;
					}));
		}

		return AsyncScheduler.joinAll(futures);
	}

	/**
	 * Runs listeners one after another, each on a worker, stopping at the first failure.
	 *
	 * <p>The caller usually blocks on the returned future (lifecycle moments must finish
	 * before the game moves on); see {@link AsyncAwait}.
	 *
	 * @param listeners the listeners to run, in order
	 * @param call starts one listener
	 * @param <T> listener type
	 * @return a future completing when all listeners ran or one failed
	 */
	public static <T> CompletableFuture<Void> chainAll(T[] listeners, Function<T, CompletableFuture<Void>> call) {
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

		for (T listener : listeners) {
			chain = chain.thenCompose(ignored -> AsyncScheduler.supplyAsync(() -> call.apply(listener))
					.thenCompose(Function.identity()));
		}

		return chain;
	}

	/**
	 * Concurrent dispatch that skips a call while the previous one is still running.
	 *
	 * <p>Used by tick events: ticks must not queue up behind a slow listener. Skipped calls
	 * are counted so the slowness is visible.
	 *
	 * @param name gate name, also used for failure counting
	 * @param listeners the listeners to run
	 * @param call starts one listener
	 * @param <T> listener type
	 * @return a future completing when this batch finished, or immediately if skipped
	 */
	public static <T> CompletableFuture<Void> gatedParallel(String name, T[] listeners, Function<T, CompletableFuture<Void>> call) {
		if (listeners.length == 0) {
			return CompletableFuture.completedFuture(null);
		}

		Gate gate = GATES.computeIfAbsent(name, ignored -> new Gate());

		if (!gate.inFlight.compareAndSet(false, true)) {
			gate.dropped.incrementAndGet();
			return CompletableFuture.completedFuture(null);
		}

		return parallel(name, listeners, call).whenComplete((ignored, throwable) -> gate.inFlight.set(false));
	}

	/** @return how many calls were skipped because the previous batch had not finished */
	public static long dropped(String name) {
		Gate gate = GATES.get(name);

		return gate != null ? gate.dropped.get() : 0;
	}

	/** Test hook: forget all gate state. */
	public static void reset() {
		GATES.clear();
		ListenerFailures.reset();
	}

	private static final class Gate {
		private final AtomicBoolean inFlight = new AtomicBoolean();
		private final AtomicLong dropped = new AtomicLong();
	}
}
