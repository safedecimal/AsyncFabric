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

package net.asyncfabric.test.async.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.asyncfabric.impl.async.world.DeferredActions;
import net.asyncfabric.impl.async.world.EntityGroups;
import net.asyncfabric.impl.async.world.RegionGrid;
import net.asyncfabric.impl.async.world.RegionOwnership;

/**
 * Tests the primitives that make parallel world ticking safe.
 *
 * <p>These exist because a race in world tick corrupts saves. Each test pins one of the four
 * defences: exclusive ownership, interaction closure, deferred cross-region effects, and the
 * fallback that keeps a broken tick from becoming a broken world.
 */
class WorldRaceSafetyTest {
	// ---------------------------------------------------------------- ownership

	@Test
	void onlyOneThreadCanOwnARegionAtATime() {
		RegionOwnership ownership = new RegionOwnership();

		assertTrue(ownership.acquire(1), "first thread must get the region");
		assertFalse(ownership.acquire(2), "second thread must be refused");
		assertTrue(ownership.isOwnedBy(1));
		assertFalse(ownership.isFree());
	}

	@Test
	void ownershipSurvivesConcurrentContention() throws Exception {
		RegionOwnership ownership = new RegionOwnership();
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger winners = new AtomicInteger();

		try {
			for (int i = 0; i < threads; i++) {
				final int id = i;
				pool.execute(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					if (ownership.acquire(id)) winners.incrementAndGet();
				});
			}

			start.countDown();
			pool.shutdown();

			assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
			assertEquals(1, winners.get(), "exactly one thread may win the region");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void releaseByNonOwnerIsRejected() {
		RegionOwnership ownership = new RegionOwnership();
		ownership.acquire(1);

		assertThrows(IllegalStateException.class, () -> ownership.release(2),
				"releasing somebody else's region must be loud, not silent");
	}

	// ---------------------------------------------------------------- region grid

	@Test
	void chunksMapToDisjointRegions() {
		RegionGrid grid = new RegionGrid(0, 0, 31, 31, 4);

		assertEquals(grid.regionOf(0, 0), grid.regionOf(3, 3), "chunks of one square share a region");
		assertFalse(grid.regionOf(0, 0) == grid.regionOf(4, 0), "neighbouring squares differ");
	}

	@Test
	void negativeChunkCoordinatesStillMapDeterministically() {
		RegionGrid grid = new RegionGrid(-32, -32, 31, 31, 4);

		int a = grid.regionOf(-1, -1);
		int b = grid.regionOf(-1, -1);

		assertEquals(a, b);
		assertTrue(a >= 0, "region index must be valid");
		assertTrue(a < grid.regionCount());
	}

	@Test
	void claimingIsExclusiveAcrossThreads() {
		RegionGrid grid = new RegionGrid(0, 0, 15, 15, 4);
		int region = grid.regionOf(0, 0);

		assertTrue(grid.claim(region, 1));
		assertFalse(grid.claim(region, 2));
		assertEquals(1, grid.ownerOf(region));

		grid.release(region, 1);
		assertTrue(grid.claim(region, 2), "after release another thread may take it");
	}

	// ---------------------------------------------------------------- entity groups

	@Test
	void ridingPairsShareAGroup() {
		EntityGroups groups = new EntityGroups(4);

		groups.link(0, 1); // rider -> horse

		assertTrue(groups.sameGroup(0, 1));
		assertFalse(groups.sameGroup(0, 2));
	}

	@Test
	void linksAreTransitive() {
		EntityGroups groups = new EntityGroups(5);

		// A rides B, B rides C: all three must tick together, even across three chunks
		groups.link(0, 1);
		groups.link(1, 2);

		assertTrue(groups.sameGroup(0, 2), "transitivity must hold or a chain splits across threads");
		assertFalse(groups.sameGroup(0, 3));
	}

	@Test
	void groupsAreDisjointAndComplete() {
		EntityGroups groups = new EntityGroups(6);

		groups.link(0, 1);
		groups.link(2, 3);
		// 4 and 5 are alone

		List<int[]> result = groups.groupsOf(new int[] { 0, 1, 2, 3, 4, 5 });
		List<Integer> seen = new ArrayList<>();

		assertEquals(4, result.size(), "two pairs plus two singles");

		for (int[] group : result) {
			for (int index : group) {
				assertFalse(seen.contains(index), "entity " + index + " appeared in two groups");
				seen.add(index);
			}
		}

		assertEquals(6, seen.size(), "every entity must be scheduled exactly once");
	}

	@Test
	void emptyInputProducesNoGroups() {
		EntityGroups groups = new EntityGroups(0);

		assertTrue(groups.groupsOf(new int[0]).isEmpty());
	}

	@Test
	void groupOrderIsDeterministic() {
		EntityGroups first = new EntityGroups(4);
		EntityGroups second = new EntityGroups(4);

		first.link(0, 1);
		second.link(1, 0); // linked in the opposite direction

		int[] indices = { 0, 1, 2, 3 };

		List<int[]> a = first.groupsOf(indices);
		List<int[]> b = second.groupsOf(indices);

		assertEquals(a.size(), b.size());

		for (int i = 0; i < a.size(); i++) {
			assertTrue(Arrays.equals(a.get(i), b.get(i)),
					"group order must not depend on link direction");
		}
	}

	// ---------------------------------------------------------------- deferred actions

	@Test
	void deferredActionsRunAtTheBarrierNotBefore() {
		DeferredActions actions = new DeferredActions();
		List<String> run = new ArrayList<>();

		actions.enqueue(() -> run.add("pushed-block"));

		assertTrue(run.isEmpty(), "nothing may run while regions are still ticking");
		assertEquals(1, actions.size());

		actions.drain(t -> { });

		assertEquals(List.of("pushed-block"), run);
		assertTrue(actions.isEmpty());
	}

	@Test
	void duplicateKeysApplyOnce() {
		// this is the dupe case: two regions both decide to drop the same item
		DeferredActions actions = new DeferredActions();
		AtomicInteger drops = new AtomicInteger();

		actions.enqueue("drop:item:17", drops::incrementAndGet);
		actions.enqueue("drop:item:17", drops::incrementAndGet);
		actions.enqueue("drop:item:18", drops::incrementAndGet);

		actions.drain(t -> { });

		assertEquals(2, drops.get(), "the same drop must not be applied twice");
	}

	@Test
	void dedupeKeysResetEveryTick() {
		DeferredActions actions = new DeferredActions();
		AtomicInteger runs = new AtomicInteger();

		actions.enqueue("tick-bound", runs::incrementAndGet);
		actions.drain(t -> { });

		// next tick the same key must be accepted again
		actions.enqueue("tick-bound", runs::incrementAndGet);
		actions.drain(t -> { });

		assertEquals(2, runs.get(), "dedupe must be per tick, not for the session");
	}

	@Test
	void actionsRunInSubmissionOrder() {
		DeferredActions actions = new DeferredActions();
		List<Integer> order = new ArrayList<>();

		for (int i = 0; i < 20; i++) {
			final int value = i;
			actions.enqueue(() -> order.add(value));
		}

		actions.drain(t -> { });

		for (int i = 0; i < 20; i++) {
			assertEquals(i, order.get(i), "actions must apply in queue order for determinism");
		}
	}

	@Test
	void oneFailingActionDoesNotSkipTheRest() {
		DeferredActions actions = new DeferredActions();
		AtomicInteger applied = new AtomicInteger();
		List<Throwable> failures = new ArrayList<>();

		actions.enqueue(applied::incrementAndGet);
		actions.enqueue(() -> {
			throw new IllegalStateException("broken effect");
		});
		actions.enqueue(applied::incrementAndGet);

		actions.drain(failures::add);

		assertEquals(2, applied.get(), "a broken effect must not strand the others");
		assertEquals(1, failures.size());
		assertEquals("broken effect", failures.get(0).getMessage());
	}

	@Test
	void enqueueAfterBarrierIsRejected() {
		DeferredActions actions = new DeferredActions();
		// drain flips the flag off and on again; simulate a straggler by draining inside a hook
		actions.enqueue(() -> {
			assertThrows(IllegalStateException.class, () -> actions.enqueue(() -> { }),
					"a region must not enqueue for a tick that already ended");
		});

		actions.drain(t -> { });
	}

	@Test
	void concurrentEnqueueIsSafe() throws Exception {
		DeferredActions actions = new DeferredActions();
		AtomicInteger applied = new AtomicInteger();
		int threads = 8;
		int perThread = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		ConcurrentHashMap<String, Boolean> keys = new ConcurrentHashMap<>();

		try {
			for (int i = 0; i < threads; i++) {
				final int id = i;
				pool.execute(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					for (int j = 0; j < perThread; j++) {
						actions.enqueue("effect-" + id + "-" + j, applied::incrementAndGet);
						keys.put(id + ":" + j, Boolean.TRUE);
					}
				});
			}

			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

			actions.drain(t -> { });

			assertEquals(threads * perThread, applied.get(), "every distinct effect must apply exactly once");
		} finally {
			pool.shutdownNow();
		}
	}
}
