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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.asyncfabric.impl.async.world.BlockWriteQueue;
import net.asyncfabric.impl.async.world.ParallelChunkTick;
import net.asyncfabric.impl.async.world.RaceGuard;
import net.asyncfabric.impl.async.world.RegionGrid;
import net.asyncfabric.impl.async.world.TickOutcome;
import net.asyncfabric.impl.async.world.TickPlan;

/**
 * Tests parallel chunk and block-entity ticking.
 *
 * <p>What matters here is the same as for entities: every chunk is ticked once, work leaves the
 * game thread, the barrier holds, and unsafe tickers never reach a worker.
 */
class ParallelChunkTickTest {
	private final ParallelChunkTick ticker = new ParallelChunkTick(new RaceGuard());

	@AfterEach
	void resetGuard() {
		ticker.guard().reset();
	}

	/** Packs a plan and grid for the given chunks, the same way the world tick does. */
	private static final class Arrangement {
		final TickPlan plan;
		final RegionGrid grid;

		Arrangement(TickPlan plan, RegionGrid grid) {
			this.plan = plan;
			this.grid = grid;
		}

		TickPlan plan() {
			return plan;
		}

		RegionGrid grid() {
			return grid;
		}
	}

	private static Arrangement arrangement(int[] chunkXs, int[] chunkZs) {
		int size = RegionGrid.defaultRegionSize();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int i = 0; i < chunkXs.length; i++) {
			minX = Math.min(minX, chunkXs[i]);
			maxX = Math.max(maxX, chunkXs[i]);
			minZ = Math.min(minZ, chunkZs[i]);
			maxZ = Math.max(maxZ, chunkZs[i]);
		}

		return new Arrangement(TickPlan.build(chunkXs, chunkZs, List.of(), size),
				new RegionGrid(minX, minZ, maxX, maxZ, size));
	}

	@Test
	void everyChunkTicksExactlyOnce() {
		int[] xs = new int[20];
		int[] zs = new int[20];

		for (int i = 0; i < 20; i++) {
			xs[i] = i * 4;
			zs[i] = 0;
		}

		Arrangement arrangement = arrangement(xs, zs);
		Set<Integer> ticked = ConcurrentHashMap.newKeySet();
		AtomicInteger count = new AtomicInteger();

		TickOutcome outcome = ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> {
					ticked.add(index);
					count.incrementAndGet();
				}, new BlockWriteQueue(), t -> { });

		assertEquals(TickOutcome.COMPLETED, outcome);
		assertEquals(20, count.get(), "a chunk ticked twice would duplicate its random ticks");
		assertEquals(20, ticked.size(), "no chunk may be skipped");
	}

	@Test
	void chunkTicksLeaveTheGameThread() {
		int[] xs = { 0, 8, 16, 24, 32, 40, 48, 56 };
		int[] zs = { 0, 0, 0, 0, 0, 0, 0, 0 };

		Arrangement arrangement = arrangement(xs, zs);
		Thread caller = Thread.currentThread();
		Set<String> threads = ConcurrentHashMap.newKeySet();

		ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> threads.add(Thread.currentThread().getName()), new BlockWriteQueue(), t -> { });

		assertFalse(threads.contains(caller.getName()), "random ticks must leave the game thread");
	}

	@Test
	void barrierWaitsForEveryChunk() throws Exception {
		int[] xs = { 0, 8, 16, 24, 32, 40 };
		int[] zs = { 0, 0, 0, 0, 0, 0 };

		Arrangement arrangement = arrangement(xs, zs);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger finished = new AtomicInteger();

		Thread runner = new Thread(() -> ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> {
					try {
						release.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}

					finished.incrementAndGet();
				}, new BlockWriteQueue(), t -> { }));

		runner.start();
		Thread.sleep(300);
		assertEquals(0, finished.get(), "the tick must not return before its chunks finished");

		release.countDown();
		runner.join(20000);

		assertEquals(6, finished.get());
	}

	@Test
	void deferredBlockWritesAreAppliedAtTheBarrier() {
		int[] xs = { 0, 8, 16, 24, 32, 40 };
		int[] zs = { 0, 0, 0, 0, 0, 0 };

		Arrangement arrangement = arrangement(xs, zs);
		BlockWriteQueue writes = new BlockWriteQueue();
		AtomicInteger applied = new AtomicInteger();

		ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> writes.submit(index, applied::incrementAndGet), writes, t -> { });

		assertEquals(6, applied.get(), "every cross-region write must reach the world exactly once");
		assertTrue(writes.isEmpty(), "the queue must be drained at the barrier");
	}

	@Test
	void singleChunkIsNotWorthParallelising() {
		int[] xs = { 0 };
		int[] zs = { 0 };

		Arrangement arrangement = arrangement(xs, zs);
		AtomicInteger ticks = new AtomicInteger();

		TickOutcome outcome = ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> ticks.incrementAndGet(), new BlockWriteQueue(), t -> { });

		assertEquals(TickOutcome.SKIPPED, outcome, "the caller runs vanilla for a single chunk");
		assertEquals(0, ticks.get());
	}

	@Test
	void brokenChunkDoesNotStopTheRest() {
		int[] xs = { 0, 8, 16, 24, 32, 40 };
		int[] zs = { 0, 0, 0, 0, 0, 0 };

		Arrangement arrangement = arrangement(xs, zs);
		AtomicInteger ticked = new AtomicInteger();
		List<Throwable> failures = new ArrayList<>();

		ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs, index -> {
			ticked.incrementAndGet();

			if (ticked.get() == 2) throw new IllegalStateException("one chunk is broken");
		}, new BlockWriteQueue(), failures::add);

		assertEquals(6, ticked.get(), "a broken chunk must not abandon the rest");
		assertEquals(1, failures.size());
	}

	@Test
	void disabledGuardRunsNothing() {
		int[] xs = { 0, 8, 16, 24, 32, 40 };
		int[] zs = { 0, 0, 0, 0, 0, 0 };

		// drive the guard into its disabled state
		for (int i = 0; i < 5; i++) {
			ticker.guard().recordFallback(i, new IllegalStateException("boom"));
		}

		assertFalse(ticker.guard().isParallelEnabled());

		Arrangement arrangement = arrangement(xs, zs);
		AtomicInteger ticks = new AtomicInteger();

		TickOutcome outcome = ticker.tickChunks(arrangement.plan(), arrangement.grid(), xs, zs,
				index -> ticks.incrementAndGet(), new BlockWriteQueue(), t -> { });

		assertEquals(TickOutcome.SKIPPED, outcome);
		assertEquals(0, ticks.get(), "a disabled guard must not tick anything");
	}

	@Test
	void blockEntitiesFollowTheSafetySplit() {
		// four tickers: two safe, two unsafe
		int[] xs = { 0, 8, 16, 24 };
		int[] zs = { 0, 0, 0, 0 };
		int[] safe = { 0, 2 };

		Arrangement arrangement = arrangement(xs, zs);
		Set<Integer> ticked = ConcurrentHashMap.newKeySet();

		TickOutcome outcome = ticker.tickBlockEntities(arrangement.plan(), arrangement.grid(),
				xs, zs, safe, index -> ticked.add(index), new BlockWriteQueue(), t -> { });

		assertEquals(TickOutcome.COMPLETED, outcome);
		assertEquals(Set.of(0, 2), ticked, "only the safe tickers may reach a worker");
	}

	@Test
	void oneSafeBlockEntityIsNotParallelised() {
		int[] xs = { 0 };
		int[] zs = { 0 };
		int[] safe = { 0 };

		Arrangement arrangement = arrangement(xs, zs);
		AtomicInteger ticks = new AtomicInteger();

		TickOutcome outcome = ticker.tickBlockEntities(arrangement.plan(), arrangement.grid(),
				xs, zs, safe, index -> ticks.incrementAndGet(), new BlockWriteQueue(), t -> { });

		assertEquals(TickOutcome.SKIPPED, outcome);
		assertEquals(0, ticks.get());
	}
}
