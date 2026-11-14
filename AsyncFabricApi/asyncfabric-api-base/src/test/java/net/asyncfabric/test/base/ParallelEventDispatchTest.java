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

package net.asyncfabric.test.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.asyncfabric.api.event.Event;
import net.asyncfabric.api.event.EventDispatch;
import net.asyncfabric.api.event.EventFactory;
import net.asyncfabric.api.event.EventThreadMode;
import net.asyncfabric.impl.base.event.EventWorkerPool;

/**
 * Covers the automatic parallel dispatch of {@code EventFactory} events.
 *
 * <p>The point of the default is that a mod author writes an ordinary listener and still gets
 * parallelism, so these tests use plain listeners and plain {@link EventFactory#createArrayBacked}
 * calls — no opt-in API is exercised.
 */
class ParallelEventDispatchTest {
	@BeforeEach
	void reset() {
		EventWorkerPool.resetForTests();
	}

	@AfterAll
	static void shutdownPool() {
		EventWorkerPool.shutdown();
	}

	@Test
	void listenersRunOnWorkerThreads() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		Thread caller = Thread.currentThread();
		Set<String> threads = ConcurrentHashMap.newKeySet();
		AtomicInteger onCaller = new AtomicInteger();

		for (int i = 0; i < 6; i++) {
			event.register(() -> {
				threads.add(Thread.currentThread().getName());

				if (Thread.currentThread() == caller) onCaller.incrementAndGet();
			});
		}

		event.invoker().onEvent();

		assertEquals(0, onCaller.get(), "listeners must not run on the emitting thread");
		assertFalse(threads.isEmpty(), "listeners must run somewhere");
	}

	@Test
	void everyListenerRunsExactlyOnce() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		int count = 24;
		AtomicInteger invocations = new AtomicInteger();

		for (int i = 0; i < count; i++) {
			event.register(invocations::incrementAndGet);
		}

		event.invoker().onEvent();

		assertEquals(count, invocations.get());
	}

	@Test
	void emissionWaitsForEveryListener() throws Exception {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		int count = 8;
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger finished = new AtomicInteger();

		for (int i = 0; i < count; i++) {
			event.register(() -> {
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				finished.incrementAndGet();
			});
		}

		Thread emitter = new Thread(() -> event.invoker().onEvent());
		emitter.start();

		// give the pool time to start every listener, then confirm nothing returned yet
		Thread.sleep(300);
		assertEquals(0, finished.get(), "invoker returned before its listeners finished");

		release.countDown();
		emitter.join(20000);

		assertEquals(count, finished.get(), "the invoker must have waited for all listeners");
	}

	@Test
	void listenersOverlapInTime() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		// a barrier of N parties only opens if at least N listeners run at the same time
		int parties = Math.min(4, EventWorkerPool.resolveThreadCount());
		java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(parties);
		AtomicInteger overlapped = new AtomicInteger();

		for (int i = 0; i < parties; i++) {
			event.register(() -> {
				try {
					barrier.await(5, TimeUnit.SECONDS);
					overlapped.incrementAndGet();
				} catch (Exception e) {
					// timeout means the listeners did not actually overlap
				}
			});
		}

		event.invoker().onEvent();

		assertEquals(parties, overlapped.get(), "listeners did not run concurrently");
	}

	@Test
	void failingListenerDoesNotStopTheOthersAndIsRethrown() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		AtomicInteger ran = new AtomicInteger();

		for (int i = 0; i < 5; i++) {
			final int id = i;

			event.register(() -> {
				ran.incrementAndGet();

				if (id == 2) throw new IllegalStateException("listener 2 is broken");
			});
		}

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> event.invoker().onEvent());

		assertEquals(5, ran.get(), "a failing listener must not abandon the rest");
		assertEquals("listener 2 is broken", thrown.getMessage());
	}

	@Test
	void valueReturningListenersStaySequential() {
		// a value-returning listener feeds its result back into the loop; it cannot be deferred
		Event<FlagListener> event = EventFactory.createArrayBacked(FlagListener.class, listeners -> () -> {
			boolean allowed = true;

			for (FlagListener listener : listeners) {
				allowed &= listener.allow();
			}

			return allowed;
		});

		Thread caller = Thread.currentThread();
		List<Thread> seen = new CopyOnWriteArrayList<>();

		event.register(() -> {
			seen.add(Thread.currentThread());
			return true;
		});

		event.register(() -> {
			seen.add(Thread.currentThread());
			return false;
		});

		assertFalse(event.invoker().allow(), "the transformed result must be preserved");
		assertEquals(2, seen.size());

		for (Thread thread : seen) {
			assertEquals(caller, thread, "value-returning listeners must stay on the emitting thread");
		}
	}

	@Test
	void mainThreadEventsKeepVanillaOrdering() {
		Event<MainThreadListener> event = EventFactory.createArrayBacked(MainThreadListener.class, listeners -> () -> {
			for (MainThreadListener listener : listeners) {
				listener.onEvent();
			}
		});

		Thread caller = Thread.currentThread();
		List<Thread> seen = new CopyOnWriteArrayList<>();

		for (int i = 0; i < 4; i++) {
			event.register(() -> seen.add(Thread.currentThread()));
		}

		event.invoker().onEvent();

		assertEquals(4, seen.size());

		for (Thread thread : seen) {
			assertEquals(caller, thread, "@EventDispatch(MAIN_THREAD) must not leave the emitting thread");
		}
	}

	@Test
	void nestedEmissionDoesNotDeadlock() throws Exception {
		// the inner listener emits the outer event again; inline nesting must keep this finite
		Event<VoidListener> inner = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		AtomicInteger depth = new AtomicInteger();
		AtomicInteger maxDepth = new AtomicInteger();

		inner.register(() -> {
			int current = depth.incrementAndGet();
			maxDepth.accumulateAndGet(current, Math::max);

			try {
				if (current < 3) inner.invoker().onEvent();
			} finally {
				depth.decrementAndGet();
			}
		});

		Thread emitter = new Thread(() -> inner.invoker().onEvent());
		emitter.start();
		emitter.join(20000);

		assertFalse(emitter.isAlive(), "nested emission deadlocked");
		assertTrue(maxDepth.get() >= 2, "the nested emission did not run");
	}

	@Test
	void singleListenerSkipsTheDispatcher() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		Thread caller = Thread.currentThread();
		List<Thread> seen = new CopyOnWriteArrayList<>();

		event.register(() -> seen.add(Thread.currentThread()));
		event.invoker().onEvent();

		// proxy overhead for a single listener is pure cost; it stays inline
		assertEquals(caller, seen.get(0));
	}

	@Test
	void noListenersIsANoOp() {
		Event<VoidListener> event = EventFactory.createArrayBacked(VoidListener.class, listeners -> () -> {
			for (VoidListener listener : listeners) {
				listener.onEvent();
			}
		});

		assertNotNull(event.invoker());
		event.invoker().onEvent();
	}

	@FunctionalInterface
	interface VoidListener {
		void onEvent();
	}

	@FunctionalInterface
	interface FlagListener {
		boolean allow();
	}

	@EventDispatch(EventThreadMode.MAIN_THREAD)
	@FunctionalInterface
	interface MainThreadListener {
		void onEvent();
	}
}
