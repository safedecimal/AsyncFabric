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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

import net.asyncfabric.api.async.v1.AsyncScheduler;

/**
 * Owns the worker pool used by every async event.
 *
 * <p>The pool is deliberately separate from vanilla's executors: chunk generation, sound
 * streaming and IO already saturate those, and starving them would stutter the game.
 */
public final class AsyncSchedulerImpl {
	private static final int DEFAULT_MAX_THREADS = 4;
	private static final AtomicInteger THREAD_INDEX = new AtomicInteger();

	private static volatile ForkJoinPool pool;
	private static volatile int configuredThreads;

	private AsyncSchedulerImpl() {
	}

	/** @return the worker pool, creating it on first use */
	public static Executor executor() {
		return getPool();
	}

	public static int threadCount() {
		return getPool().getPoolSize();
	}

	public static boolean isWorkerThread() {
		return Thread.currentThread() instanceof AsyncWorkerThread;
	}

	private static ForkJoinPool getPool() {
		ForkJoinPool ret = pool;

		if (ret == null) {
			synchronized (AsyncSchedulerImpl.class) {
				ret = pool;

				if (ret == null) {
					configuredThreads = resolveThreadCount();
					ret = new ForkJoinPool(configuredThreads, AsyncWorkerThread::new, AsyncSchedulerImpl::onWorkerFailure, true);
					pool = ret;
				}
			}
		}

		return ret;
	}

	private static int resolveThreadCount() {
		int cores = Runtime.getRuntime().availableProcessors();
		int requested = Integer.getInteger(AsyncScheduler.THREADS_PROPERTY, Math.min(DEFAULT_MAX_THREADS, cores));

		return Math.max(1, requested);
	}

	private static void onWorkerFailure(Thread thread, Throwable throwable) {
		ListenerFailures.record(thread.getName(), throwable);
	}

	/**
	 * Joins futures without cancelling the survivors. {@link CompletableFuture#allOf}
	 * stops propagating as soon as one input fails, which would hide the listeners that
	 * did run; this keeps waiting for all of them and reports the first failure after.
	 */
	public static CompletableFuture<Void> joinAll(Collection<? extends CompletableFuture<?>> futures) {
		if (futures.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		List<CompletableFuture<?>> inputs = new ArrayList<>(futures);
		CompletableFuture<Void> all = CompletableFuture.allOf(inputs.toArray(CompletableFuture[]::new));

		// allOf already fails fast on the first failure, but the remaining futures keep
		// running; swallow their failure so nothing is logged as uncaught
		for (CompletableFuture<?> future : inputs) {
			future.exceptionally(throwable -> null);
		}

		return all;
	}

	/** @return the configured worker count, without forcing pool creation */
	public static int configuredThreadCount() {
		return configuredThreads > 0 ? configuredThreads : resolveThreadCount();
	}

	/** Shuts the pool down; used by tests to avoid leaking threads between runs. */
	public static void shutdown() {
		ForkJoinPool current = pool;

		if (current != null) {
			current.shutdownNow();
			pool = null;
		}
	}

	/** Named so worker threads are recognisable in logs and thread dumps. */
	static final class AsyncWorkerThread extends ForkJoinWorkerThread {
		AsyncWorkerThread(ForkJoinPool pool) {
			super(pool);
			setName("AsyncFabric-Worker-" + THREAD_INDEX.getAndIncrement());
			setDaemon(true);
		}
	}
}
