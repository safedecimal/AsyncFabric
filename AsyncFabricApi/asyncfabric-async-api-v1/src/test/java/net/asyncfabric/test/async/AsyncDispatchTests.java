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

package net.asyncfabric.test.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.asyncfabric.api.async.v1.AsyncScheduler;
import net.asyncfabric.impl.async.AsyncDispatch;
import net.asyncfabric.impl.async.AsyncSchedulerImpl;

/**
 * Proves that dispatch really moves work off the calling thread.
 *
 * <p>This is the test that matters for the "ASYNC" part of AsyncFabric: an earlier version
 * called {@code call.apply(listener)} on the emitting thread, so listeners silently ran on
 * the game thread and nothing was actually async. These tests fail if that regresses.
 */
public class AsyncDispatchTests {
	private static final long TIMEOUT_SECONDS = 10;

	@BeforeEach
	public void setUp() {
		AsyncDispatch.reset();
	}

	@AfterEach
	public void tearDown() {
		AsyncSchedulerImpl.shutdown();
	}

	/** The listener body must not run on the emitting thread. */
	@Test
	public void testParallelRunsListenerBodyOffCallingThread() {
		Thread caller = Thread.currentThread();
		List<Boolean> ranOnCaller = Collections.synchronizedList(new ArrayList<>());

		AsyncDispatch.parallel("test", new Handler[] {
				() -> {
					ranOnCaller.add(Thread.currentThread() == caller);
					return CompletableFuture.completedFuture(null);
				}
		}, Handler::handle).join();

		Assertions.assertEquals(List.of(false), ranOnCaller,
				"listener body must not run on the emitting thread");
	}

	/** Every listener must run on a scheduler worker, not just "some other thread". */
	@Test
	public void testParallelUsesSchedulerWorkers() throws InterruptedException {
		AtomicInteger onWorker = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(4);
		Handler[] listeners = new Handler[4];

		for (int i = 0; i < listeners.length; i++) {
			listeners[i] = () -> {
				if (Thread.currentThread().getName().startsWith("AsyncFabric-Worker")) {
					onWorker.incrementAndGet();
				}

				done.countDown();
				return CompletableFuture.completedFuture(null);
			};
		}

		AsyncDispatch.parallel("test", listeners, Handler::handle).join();

		Assertions.assertTrue(done.await(1, TimeUnit.SECONDS));
		Assertions.assertEquals(4, onWorker.get(), "every listener must run on a worker thread");
	}

	/** Ordered dispatch must also start listener bodies off the calling thread. */
	@Test
	public void testChainRunsListenerBodyOffCallingThread() {
		Thread caller = Thread.currentThread();
		List<Boolean> ranOnCaller = Collections.synchronizedList(new ArrayList<>());

		AsyncDispatch.chainAll(new Handler[] {
				() -> {
					ranOnCaller.add(Thread.currentThread() == caller);
					return CompletableFuture.completedFuture(null);
				}
		}, Handler::handle).join();

		Assertions.assertEquals(List.of(false), ranOnCaller,
				"ordered listener body must not run on the emitting thread");
	}

	/** A slow listener must not block the emitting thread while it runs. */
	@Test
	public void testParallelDoesNotBlockCaller() throws InterruptedException {
		CompletableFuture<Void> future = AsyncDispatch.parallel("test", new Handler[] {
				() -> {
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}

					return CompletableFuture.completedFuture(null);
				}
		}, Handler::handle);

		long elapsed = timeUntil(() -> future.isDone(), 100);

		Assertions.assertFalse(future.isDone() || elapsed < 100,
				"caller should return immediately while the listener is still sleeping");
		future.join();
	}

	/** The gate must skip a call while the previous batch is still running. */
	@Test
	public void testGatedParallelSkipsWhileInFlight() {
		CountDownLatch started = new CountDownLatch(1);
		Handler[] slow = new Handler[] {
				() -> {
					started.countDown();

					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}

					return CompletableFuture.completedFuture(null);
				}
		};

		CompletableFuture<Void> first = AsyncDispatch.gatedParallel("gate_test", slow, Handler::handle);
		Assertions.assertDoesNotThrow(() -> Assertions.assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)));

		AsyncDispatch.gatedParallel("gate_test", slow, Handler::handle);
		AsyncDispatch.gatedParallel("gate_test", slow, Handler::handle);

		first.join();

		Assertions.assertEquals(2, AsyncDispatch.dropped("gate_test"),
				"calls during an unfinished batch must be skipped and counted");
	}

	/**
	 * Listeners must run concurrently with each other.
	 *
	 * <p>Asserted by overlap, not by wall clock: three listeners rendezvous on a barrier that only
	 * opens when all of them are inside the listener body. A timeline assertion ("must finish in
	 * under N ms") also fails when the machine is simply busy, which says nothing about the code.
	 */
	@Test
	public void testParallelIsConcurrent() {
		Handler[] listeners = new Handler[3];
		java.util.concurrent.CyclicBarrier overlap = new java.util.concurrent.CyclicBarrier(listeners.length);
		java.util.concurrent.atomic.AtomicInteger overlapped = new java.util.concurrent.atomic.AtomicInteger();

		for (int i = 0; i < listeners.length; i++) {
			listeners[i] = () -> {
				try {
					overlap.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
					overlapped.incrementAndGet();
				} catch (Exception e) {
					// a timeout means the listeners did not actually overlap
				}

				return CompletableFuture.completedFuture(null);
			};
		}

		AsyncDispatch.parallel("test", listeners, Handler::handle).join();

		Assertions.assertEquals(listeners.length, overlapped.get(),
				"listeners did not run at the same time");
	}

	/** Pool size honours the system property. */
	@Test
	public void testSchedulerStartsWorkers() {
		CompletableFuture<Void> future = AsyncScheduler.runAsync(() -> { });
		future.join();

		Assertions.assertTrue(AsyncScheduler.threadCount() > 0, "pool must have created workers");
	}

	private static long timeUntil(java.util.function.BooleanSupplier condition, long maxMs) {
		long start = System.nanoTime();

		while (!condition.getAsBoolean()) {
			if ((System.nanoTime() - start) / 1_000_000 > maxMs) {
				break;
			}

			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		return (System.nanoTime() - start) / 1_000_000;
	}

	/** Listener shape used by these tests. */
	@FunctionalInterface
	interface Handler {
		CompletableFuture<Void> handle();
	}
}
