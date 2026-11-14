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

package net.asyncfabric.impl.base.event;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker pool used by event dispatch.
 *
 * <p>Separate from vanilla's executors on purpose: chunk generation, sound streaming and IO
 * already keep those busy, and borrowing them would trade listener parallelism for stutter.
 *
 * <p>Threads are daemon and named, so they neither keep the JVM alive nor hide in a thread dump.
 * Size defaults to {@code min(4, cores)} and can be overridden with
 * {@code -Dasyncfabric.events.threads=N}.
 */
public final class EventWorkerPool {
	/** System property controlling the dispatch pool size. */
	public static final String THREADS_PROPERTY = "asyncfabric.events.threads";
	private static final int DEFAULT_MAX_THREADS = 4;
	private static final AtomicInteger THREAD_INDEX = new AtomicInteger();

	private static volatile ForkJoinPool pool;

	private EventWorkerPool() {
	}

	/** @return the shared dispatch pool, created on first use */
	public static ForkJoinPool pool() {
		ForkJoinPool ret = pool;

		if (ret == null) {
			synchronized (EventWorkerPool.class) {
				ret = pool;

				if (ret == null) {
					ret = new ForkJoinPool(resolveThreadCount(), EventWorker::new, null, true);
					pool = ret;
				}
			}
		}

		return ret;
	}

	/**
	 * @return {@code true} if the calling thread is one of the dispatch workers
	 *
	 * <p>Dispatch never blocks a worker on its own pool: a listener that emits another event
	 * runs the nested dispatch inline, which keeps the pool from deadlocking on itself.
	 */
	public static boolean isWorkerThread() {
		return Thread.currentThread() instanceof EventWorker;
	}

	public static int resolveThreadCount() {
		int cores = Runtime.getRuntime().availableProcessors();
		int requested = Integer.getInteger(THREADS_PROPERTY, Math.min(DEFAULT_MAX_THREADS, cores));

		return Math.max(1, requested);
	}

	/** Test hook: shut the pool down so runs do not leak threads into each other. */
	public static void shutdown() {
		ForkJoinPool current = pool;

		if (current != null) {
			current.shutdownNow();
			pool = null;
		}
	}

	/** Test hook: clears any leftover state before a test starts. */
	public static void resetForTests() {
		shutdown();
	}

	/** Named daemon worker so dispatch threads are recognisable in logs and dumps. */
	static final class EventWorker extends ForkJoinWorkerThread {
		EventWorker(ForkJoinPool pool) {
			super(pool);
			setName("AsyncFabric-Event-" + THREAD_INDEX.getAndIncrement());
			setDaemon(true);
		}
	}
}
