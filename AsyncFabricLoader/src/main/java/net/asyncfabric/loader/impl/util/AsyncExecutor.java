/*
 * Copyright 2016 FabricMC
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

package net.asyncfabric.loader.impl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Worker pool for loader startup work.
 *
 * <p>Discovery already fans out over a pool, but the pool was plain {@code new ForkJoinPool()}:
 * non-daemon threads without names, invisible in a thread dump and able to keep the JVM alive.
 * This gives the loader one named, daemon, configurable pool and the helpers to use it.
 *
 * <p>Startup work is the target: reading mod jars, hashing, parsing manifests, resolving
 * dependencies. None of it touches game state, so it can all run off the main thread.
 */
public final class AsyncExecutor {
	public static final String THREADS_PROPERTY = "asyncfabric.loader.threads";
	private static final AtomicInteger POOL_INDEX = new AtomicInteger();
	private static volatile ForkJoinPool sharedPool;

	private AsyncExecutor() {
	}

	/**
	 * Returns the loader-wide pool, created once and reused.
	 *
	 * <p>Creating and shutting a pool per call is wasteful and, worse, can happen inside
	 * class-transformation during startup or under the test harness, where a
	 * {@code shutdownNow} disturbs an in-flight transform. One long-lived daemon pool
	 * avoids both.
	 *
	 * @return the shared pool
	 */
	public static ForkJoinPool sharedPool() {
		ForkJoinPool pool = sharedPool;

		if (pool == null) {
			synchronized (AsyncExecutor.class) {
				pool = sharedPool;

				if (pool == null) {
					pool = newPool();
					sharedPool = pool;
				}
			}
		}

		return pool;
	}

	/**
	 * @return a pool sized {@code min(cores, 4)} by default, overridable with
	 *         {@code -Dasyncfabric.loader.threads=N}; daemon threads named
	 *         {@code AsyncFabric-Loader-N}
	 */
	public static ForkJoinPool newPool() {
		int threads = resolveThreadCount();
		int index = POOL_INDEX.getAndIncrement();

		return new ForkJoinPool(threads, pool -> new LoaderWorkerThread(pool, index), null, true);
	}

	/**
	 * @return a single-threaded executor for ordered work that must not interleave
	 */
	public static ExecutorService newSerialExecutor(String name) {
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};

		return Executors.newSingleThreadExecutor(factory);
	}

	public static int resolveThreadCount() {
		int cores = Runtime.getRuntime().availableProcessors();
		int requested = Integer.getInteger(THREADS_PROPERTY, Math.min(cores, 4));

		return Math.max(1, requested);
	}

	/**
	 * Waits for every future, collecting results in submission order.
	 *
	 * <p>A failing future does not cancel the others; the first failure is rethrown after
	 * everything finished, so one broken mod jar cannot abort the scan of the rest.
	 *
	 * @param futures the futures to wait for
	 * @param <T> result type
	 * @return results in submission order, failed entries omitted
	 */
	public static <T> List<T> awaitAll(List<? extends CompletableFuture<T>> futures) {
		List<T> results = new ArrayList<>(futures.size());
		Throwable failure = null;

		for (CompletableFuture<T> future : futures) {
			try {
				results.add(future.join());
			} catch (Throwable t) {
				if (failure == null) failure = t;
			}
		}

		if (failure != null) {
			if (failure instanceof RuntimeException) throw (RuntimeException) failure;
			if (failure instanceof Error) throw (Error) failure;

			throw new RuntimeException(failure);
		}

		return results;
	}

	/**
	 * Maps a list in parallel, preserving order.
	 *
	 * @param pool pool to run on
	 * @param inputs the inputs
	 * @param mapper the work
	 * @param <I> input type
	 * @param <O> output type
	 * @return outputs in input order
	 */
	public static <I, O> List<O> map(ForkJoinPool pool, List<I> inputs, IntFunction<O> mapper) {
		if (inputs.isEmpty()) return new ArrayList<>();

		List<CompletableFuture<O>> futures = new ArrayList<>(inputs.size());

		for (int i = 0; i < inputs.size(); i++) {
			int index = i;
			futures.add(CompletableFuture.supplyAsync(() -> mapper.apply(index), pool));
		}

		return awaitAll(futures);
	}

	/** Shuts a pool down and waits briefly, for tests and failure paths. */
	public static void shutdown(ForkJoinPool pool, long timeoutSeconds) {
		if (pool == null) return;

		pool.shutdown();

		try {
			if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
				pool.shutdownNow();
			}
		} catch (InterruptedException e) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** Named daemon worker so loader threads are recognisable in logs and dumps. */
	static final class LoaderWorkerThread extends ForkJoinWorkerThread {
		LoaderWorkerThread(ForkJoinPool pool, int poolIndex) {
			super(pool);
			setName("AsyncFabric-Loader-" + poolIndex + "-" + getPoolIndex());
			setDaemon(true);
		}
	}
}
