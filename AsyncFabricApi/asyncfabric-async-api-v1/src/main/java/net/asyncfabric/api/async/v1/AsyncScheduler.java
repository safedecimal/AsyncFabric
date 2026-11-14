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

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import net.asyncfabric.impl.async.AsyncSchedulerImpl;

/**
 * Runs work off the game thread.
 *
 * <p>AsyncFabric owns a small dedicated pool so that async mod work does not compete with
 * vanilla's chunk generation, sound streaming and IO pools. The pool is created lazily on
 * first use, uses daemon threads, and its size can be controlled with the
 * {@code asyncfabric.async.threads} system property (default: {@code min(4, cores)}).
 *
 * <p>This class is the only supported way to obtain an executor for async mod work.
 * Listener code should never create its own threads.
 */
public final class AsyncScheduler {
	/** System property controlling the worker pool size. */
	public static final String THREADS_PROPERTY = "asyncfabric.async.threads";

	private AsyncScheduler() {
	}

	/**
	 * @return the executor all async mod work runs on
	 */
	public static Executor executor() {
		return AsyncSchedulerImpl.executor();
	}

	/**
	 * @return the number of worker threads in the pool
	 */
	public static int threadCount() {
		return AsyncSchedulerImpl.threadCount();
	}

	/**
	 * Runs {@code supplier} on a worker thread.
	 *
	 * @param supplier the work to run
	 * @param <T> the result type
	 * @return a future completing with the supplier's result
	 */
	public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, executor());
	}

	/**
	 * Runs {@code task} on a worker thread.
	 *
	 * @param task the work to run
	 * @return a future completing when the task finishes
	 */
	public static CompletableFuture<Void> runAsync(Runnable task) {
		return CompletableFuture.runAsync(task, executor());
	}

	/**
	 * Joins several futures into one void future that completes when all of them did.
	 *
	 * <p>Unlike {@link CompletableFuture#allOf}, a failure in one future does not cancel
	 * the others: they all run to completion, and the returned future completes
	 * exceptionally with the first failure afterwards. This keeps one misbehaving
	 * listener from starving the rest.
	 *
	 * @param futures the futures to join
	 * @return a future completing when every input future finished
	 */
	public static CompletableFuture<Void> joinAll(Collection<? extends CompletableFuture<?>> futures) {
		return AsyncSchedulerImpl.joinAll(futures);
	}

	/**
	 * @return {@code true} if the calling thread is one of this scheduler's workers
	 */
	public static boolean isWorkerThread() {
		return AsyncSchedulerImpl.isWorkerThread();
	}
}
