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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import net.asyncfabric.api.async.v1.AsyncEvent;
import net.asyncfabric.api.async.v1.AsyncEventFactory;
import net.asyncfabric.api.async.v1.AsyncScheduler;
import net.asyncfabric.impl.async.AsyncSchedulerImpl;
import net.asyncfabric.impl.async.ListenerFailures;

/**
 * Verifies the async event bus: ordering, short-circuiting, real parallelism and
 * failure isolation.
 */
public class AsyncEventTests {
	private static final long TIMEOUT_SECONDS = 10;

	@BeforeEach
	public void setUp() {
		ListenerFailures.reset();
	}

	@AfterEach
	public void tearDown() {
		AsyncSchedulerImpl.shutdown();
	}

	/** Ordered events must run listeners one at a time, in registration order. */
	@Test
	public void testOrderedRespectsRegistrationOrder() {
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());

		AsyncEvent<Step> event = AsyncEventFactory.createOrdered(Step.class, listeners -> () -> {
			CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

			for (Step listener : listeners) {
				chain = chain.thenCompose(ignored -> listener.step());
			}

			return chain;
		});

		event.register(() -> step(order, 1));
		event.register(() -> step(order, 2));
		event.register(() -> step(order, 3));

		event.invoker().step().join();

		Assertions.assertEquals(List.of(1, 2, 3), order);
	}

	/** Ordered events must stop at the first failing stage. */
	@Test
	public void testOrderedShortCircuitsOnFailure() {
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());

		AsyncEvent<Step> event = AsyncEventFactory.createOrdered(Step.class, listeners -> () -> {
			CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

			for (Step listener : listeners) {
				chain = chain.thenCompose(ignored -> listener.step());
			}

			return chain;
		});

		event.register(() -> step(order, 1));
		event.register(() -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
		event.register(() -> step(order, 3));

		Assertions.assertThrows(Exception.class, () -> event.invoker().step().join());
		Assertions.assertEquals(List.of(1), order, "stage after the failure must not run");
	}

	/** An ordered event with no listeners completes immediately. */
	@Test
	public void testEmptyOrderedEventCompletes() {
		AsyncEvent<Step> event = AsyncEventFactory.createOrdered(Step.class, listeners -> () -> {
			CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

			for (Step listener : listeners) {
				chain = chain.thenCompose(ignored -> listener.step());
			}

			return chain;
		});

		Assertions.assertDoesNotThrow(() -> event.invoker().step().join());
	}

	/**
	 * Parallel events must actually run listeners concurrently. Three listeners sleeping
	 * 200 ms each must finish well under the 600 ms a sequential run would take.
	 */
	@Test
	public void testParallelRunsConcurrently() {
		AsyncEvent<Step> event = AsyncEventFactory.createParallel(Step.class, listeners -> () -> {
			List<CompletableFuture<Void>> futures = new ArrayList<>(listeners.length);

			for (Step listener : listeners) {
				futures.add(CompletableFuture.supplyAsync(() -> {
					listener.step().join();
					return null;
				}, AsyncScheduler.executor()));
			}

			return AsyncScheduler.joinAll(futures);
		});

		CountDownLatch started = new CountDownLatch(3);

		for (int i = 0; i < 3; i++) {
			event.register(() -> CompletableFuture.runAsync(() -> {
				started.countDown();

				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}, AsyncScheduler.executor()));
		}

		long start = System.nanoTime();
		event.invoker().step().join();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		Assertions.assertTrue(elapsedMs < 500, "expected concurrent execution, took " + elapsedMs + "ms");
		Assertions.assertEquals(0, started.getCount());
	}

	/** One failing listener must not prevent the others from running. */
	@Test
	public void testParallelIsolatesFailures() {
		AsyncEvent<Step> event = AsyncEventFactory.createParallel(Step.class, listeners -> () -> {
			List<CompletableFuture<Void>> futures = new ArrayList<>(listeners.length);

			for (Step listener : listeners) {
				futures.add(listener.step().exceptionally(throwable -> {
					ListenerFailures.record("test", throwable);
					return null;
				}));
			}

			return AsyncScheduler.joinAll(futures);
		});

		CountDownLatch completed = new CountDownLatch(2);

		event.register(() -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
		event.register(() -> CompletableFuture.runAsync(completed::countDown, AsyncScheduler.executor()));
		event.register(() -> CompletableFuture.runAsync(completed::countDown, AsyncScheduler.executor()));

		event.invoker().step().join();

		Assertions.assertDoesNotThrow(() -> {
			boolean finished = completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			Assertions.assertTrue(finished, "healthy listeners must still run");
		});
	}

	/** Listeners must run on scheduler threads, never on the calling thread. */
	@Test
	public void testListenersRunOffCallingThread() {
		Thread caller = Thread.currentThread();
		List<Boolean> onCaller = Collections.synchronizedList(new ArrayList<>());

		AsyncEvent<Step> event = AsyncEventFactory.createParallel(Step.class, listeners -> () -> {
			List<CompletableFuture<Void>> futures = new ArrayList<>(listeners.length);

			for (Step listener : listeners) {
				futures.add(listener.step());
			}

			return AsyncScheduler.joinAll(futures);
		});

		event.register(() -> CompletableFuture.supplyAsync(() -> {
			onCaller.add(Thread.currentThread() == caller);
			return null;
		}, AsyncScheduler.executor()));

		event.invoker().step().join();

		Assertions.assertEquals(List.of(false), onCaller, "listener must not observe the calling thread");
	}

	/** Small sleep helper for ordered tests. */
	private static CompletableFuture<Void> step(List<Integer> order, int value) {
		return CompletableFuture.runAsync(() -> order.add(value), AsyncScheduler.executor());
	}

	/** Listener shape used by the tests. */
	@FunctionalInterface
	interface Step {
		CompletableFuture<Void> step();
	}

	/** Phase ordering must be honoured by ordered events. */
	@Test
	public void testPhaseOrdering() {
		List<String> order = Collections.synchronizedList(new ArrayList<>());

		AsyncEvent<Step> event = AsyncEventFactory.createOrderedWithPhases(
				Step.class,
				listeners -> () -> {
					CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

					for (Step listener : listeners) {
						chain = chain.thenCompose(ignored -> listener.step());
					}

					return chain;
				},
				AsyncEvent.DEFAULT_PHASE);

		Identifier late = Identifier.fromNamespaceAndPath("asyncfabric", "late");
		Identifier early = Identifier.fromNamespaceAndPath("asyncfabric", "early");

		event.register(late, () -> CompletableFuture.runAsync(() -> order.add("late"), AsyncScheduler.executor()));
		event.register(early, () -> CompletableFuture.runAsync(() -> order.add("early"), AsyncScheduler.executor()));
		event.addPhaseOrdering(early, late);

		event.invoker().step().join();

		Assertions.assertEquals(List.of("early", "late"), order);
	}
}
