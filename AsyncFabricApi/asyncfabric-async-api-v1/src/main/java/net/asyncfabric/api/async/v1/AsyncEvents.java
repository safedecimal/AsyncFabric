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

package net.asyncfabric.api.async.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Builds the common invoker shapes for {@link AsyncEventFactory}.
 *
 * <p>Declaring an async event by hand means writing a {@code thenCompose} chain (ordered)
 * or a fan-out plus reduce (parallel). These helpers spell those out once so event
 * declarations stay readable:
 *
 * <pre>{@code
 * // every listener runs, all results ANDed
 * AsyncEvent<AllowDamage> ALLOW = AsyncEventFactory.createParallel(AllowDamage.class,
 *     AsyncEvents.parallelAll(AllowDamage.class, (listener, args) -> ...));
 * }</pre>
 */
public final class AsyncEvents {
	private AsyncEvents() {
	}

	/**
	 * Chains listeners sequentially, keeping the last listener's result.
	 *
	 * <p>Every listener receives the result of the previous one. A failed stage short-circuits
	 * the chain: later listeners are not called.
	 *
	 * @param listeners the ordered listeners
	 * @param call starts one listener with the previous result
	 * @param <R> the result type
	 * @return a future completing with the last listener's result
	 */
	public static <R> CompletableFuture<R> chain(List<? extends Function<R, CompletableFuture<R>>> listeners, R initial) {
		CompletableFuture<R> result = CompletableFuture.completedFuture(initial);

		for (Function<R, CompletableFuture<R>> listener : listeners) {
			result = result.thenCompose(listener);
		}

		return result;
	}

	/**
	 * Runs every future-producing step concurrently and joins them.
	 *
	 * <p>A failing step does not prevent the others from running; the first failure is
	 * reported once all steps finished.
	 *
	 * @param futures the futures to run
	 * @return a future completing when all of them finished
	 */
	public static CompletableFuture<Void> all(List<? extends CompletableFuture<?>> futures) {
		return AsyncScheduler.joinAll(futures);
	}

	/**
	 * Runs every future-producing step concurrently and collects their results in
	 * listener order.
	 *
	 * @param futures the futures to run
	 * @param <R> the result type
	 * @return a future completing with all results, in the order the futures were passed
	 */
	public static <R> CompletableFuture<List<R>> collect(List<? extends CompletableFuture<R>> futures) {
		return AsyncScheduler.joinAll(futures).thenApply(ignored -> {
			List<R> results = new ArrayList<>(futures.size());

			for (CompletableFuture<R> future : futures) {
				// a failed listener yields null; its failure was already reported by joinAll
				results.add(future.getNow(null));
			}

			return results;
		});
	}
}
