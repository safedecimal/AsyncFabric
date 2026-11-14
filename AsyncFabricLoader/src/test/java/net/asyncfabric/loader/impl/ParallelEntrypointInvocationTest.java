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

package net.asyncfabric.loader.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.asyncfabric.loader.api.ModContainer;
import net.asyncfabric.loader.api.entrypoint.EntrypointContainer;
import net.asyncfabric.loader.api.metadata.ModMetadata;

/**
 * Covers the parallel entrypoint dispatch introduced for AsyncFabric.
 *
 * <p>AsyncFabric mods are written for this platform, so entrypoints are expected to be
 * thread-safe and independent. These tests pin the properties the platform relies on: real
 * concurrency, a join barrier before the stage returns, and aggregated failures.
 */
class ParallelEntrypointInvocationTest {
	@Test
	void allEntrypointsOfAStageRun() {
		List<FakeContainer> containers = containers(8);
		Set<Integer> seen = ConcurrentHashMap.newKeySet();

		AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, seen::add);

		assertEquals(8, seen.size());
	}

	@Test
	void invocationsActuallyLeaveTheCallingThread() {
		List<FakeContainer> containers = containers(4);
		Thread caller = Thread.currentThread();
		Set<String> threadNames = ConcurrentHashMap.newKeySet();
		AtomicInteger onCallerThread = new AtomicInteger();

		AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, id -> {
			threadNames.add(Thread.currentThread().getName());

			if (Thread.currentThread() == caller) onCallerThread.incrementAndGet();
		});

		assertEquals(0, onCallerThread.get(), "entrypoints must not run on the calling thread");
		assertTrue(threadNames.size() >= 1);
	}

	@Test
	void stageReturnsOnlyAfterAllEntrypointsFinished() throws Exception {
		int count = 6;
		List<FakeContainer> containers = containers(count);
		// every entrypoint blocks until the latch opens, so a missing barrier would deadlock
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger finished = new AtomicInteger();

		Thread runner = new Thread(() -> AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, id -> {
			try {
				latch.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			finished.incrementAndGet();
		}));

		runner.start();
		// give the pool a chance to pick up the work, then release everyone
		Thread.sleep(200);
		assertEquals(0, finished.get(), "stage returned before its entrypoints finished");

		latch.countDown();
		runner.join(20000);

		assertEquals(count, finished.get(), "every entrypoint must finish before the stage returns");
	}

	@Test
	void oneFailureDoesNotStopTheOthersAndIsRethrown() {
		List<FakeContainer> containers = containers(5);
		AtomicInteger invoked = new AtomicInteger();

		RuntimeException thrown = assertThrows(RuntimeException.class, () ->
				AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, id -> {
					invoked.incrementAndGet();

					if (id == 2) throw new IllegalStateException("mod " + id + " is broken");
				}));

		assertEquals(5, invoked.get(), "a failing entrypoint must not abandon the remaining ones");
		assertTrue(thrown.getMessage().contains("Could not execute entrypoint stage 'main'"), thrown.getMessage());
		assertTrue(rootCause(thrown) instanceof IllegalStateException, "underlying cause must be preserved");
	}

	@Test
	void multipleFailuresAreAllReported() {
		List<FakeContainer> containers = containers(4);

		RuntimeException thrown = assertThrows(RuntimeException.class, () ->
				AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, id -> {
					if (id % 2 == 0) throw new IllegalStateException("broken " + id);
				}));

		int reported = 1 + thrown.getSuppressed().length;
		assertEquals(2, reported, "both failing entrypoints must be reported");
	}

	@Test
	void emptyStageIsANoOp() {
		AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", Collections.<FakeContainer>emptyList(), id -> {
			throw new AssertionError("must not be invoked");
		});
	}

	@Test
	void entrypointsAreConstructedOnTheCallingThread() {
		List<FakeContainer> containers = containers(4);
		Thread caller = Thread.currentThread();
		Set<String> constructionThreads = ConcurrentHashMap.newKeySet();

		for (FakeContainer container : containers) {
			container.constructionThread = constructionThreads::add;
		}

		AsyncFabricLoaderImpl.invokeEntrypointsParallel("main", containers, id -> { });

		assertEquals(caller.getName(), constructionThreads.iterator().next());
	}

	private static RuntimeException rootCause(Throwable t) {
		Throwable current = t;

		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}

		return current instanceof RuntimeException ? (RuntimeException) current : new RuntimeException(current);
	}

	private static List<FakeContainer> containers(int count) {
		List<FakeContainer> list = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			list.add(new FakeContainer(i));
		}

		return list;
	}

	/** Minimal container; the invoker receives the id so tests can address one entrypoint. */
	private static final class FakeContainer implements EntrypointContainer<Integer> {
		private final int id;
		java.util.function.Consumer<String> constructionThread;

		FakeContainer(int id) {
			this.id = id;
		}

		@Override
		public Integer getEntrypoint() {
			if (constructionThread != null) constructionThread.accept(Thread.currentThread().getName());

			return id;
		}

		@Override
		public ModContainer getProvider() {
			ModContainer container = mock(ModContainer.class);
			ModMetadata metadata = mock(ModMetadata.class);
			when(metadata.getId()).thenReturn("fake_mod_" + id);
			when(container.getMetadata()).thenReturn(metadata);

			return container;
		}

		@Override
		public String getDefinition() {
			return "fake/" + id;
		}
	}
}
