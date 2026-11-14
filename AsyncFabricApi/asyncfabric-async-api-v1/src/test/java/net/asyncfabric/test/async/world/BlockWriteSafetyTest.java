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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.asyncfabric.impl.async.world.BlockEntitySafety;
import net.asyncfabric.impl.async.world.BlockWriteQueue;

/**
 * Tests the block-write safety layer.
 *
 * <p>These are the assertions that stand between parallel block ticks and a corrupted save:
 * writes that reach the barrier exactly once, collapsing that cannot merge two different blocks,
 * and a block entity deny-list that fails closed.
 */
class BlockWriteSafetyTest {
	/** Registrations are process-global, so each test starts from the built-in defaults. */
	@AfterEach
	void resetSafetyRegistrations() {
		BlockEntitySafety.resetRegistrations();
	}

	// ------------------------------------------------------------ block write queue

	@Test
	void queuedWriteDoesNotRunUntilBarrier() {
		BlockWriteQueue queue = new BlockWriteQueue();
		AtomicInteger applied = new AtomicInteger();

		assertTrue(queue.submit(1L, applied::incrementAndGet));
		assertEquals(0, applied.get(), "a queued write must not touch the world yet");

		queue.drain(t -> { });

		assertEquals(1, applied.get());
		assertTrue(queue.isEmpty());
	}

	@Test
	void everyDistinctPositionAppliesOnce() {
		BlockWriteQueue queue = new BlockWriteQueue();
		AtomicInteger applied = new AtomicInteger();

		for (long pos = 0; pos < 20; pos++) {
			final long p = pos;
			queue.submit(p, applied::incrementAndGet);
		}

		queue.drain(t -> { });

		assertEquals(20, applied.get(), "distinct blocks must each be written once");
	}

	@Test
	void samePositionCollapsesToTheLastWrite() {
		// two regions both changing one block in the same tick: vanilla would end with the second
		// state and fire one update, so collapsing must do the same
		BlockWriteQueue queue = new BlockWriteQueue();
		List<String> states = new ArrayList<>();

		queue.submit(42L, () -> states.add("first"));
		queue.submit(42L, () -> states.add("second"));

		queue.drain(t -> { });

		assertEquals(List.of("second"), states, "the block must be written once, with the last state");
	}

	@Test
	void collapsingDoesNotMergeDifferentPositions() {
		BlockWriteQueue queue = new BlockWriteQueue();
		List<Long> written = new ArrayList<>();

		queue.submit(1L, () -> written.add(1L));
		queue.submit(2L, () -> written.add(2L));
		queue.submit(1L, () -> written.add(1L));

		queue.drain(t -> { });

		assertEquals(2, written.size(), "two blocks, two writes");
		assertTrue(written.containsAll(List.of(1L, 2L)));
	}

	@Test
	void oneFailingWriteDoesNotStrandTheRest() {
		BlockWriteQueue queue = new BlockWriteQueue();
		AtomicInteger applied = new AtomicInteger();
		List<Throwable> failures = new ArrayList<>();

		queue.submit(1L, applied::incrementAndGet);
		queue.submit(2L, () -> {
			throw new IllegalStateException("broken write");
		});
		queue.submit(3L, applied::incrementAndGet);

		queue.drain(failures::add);

		assertEquals(2, applied.get(), "a broken write must not skip the others");
		assertEquals(1, failures.size());
		assertEquals("broken write", failures.get(0).getMessage());
	}

	@Test
	void writesApplyInSubmissionOrder() {
		BlockWriteQueue queue = new BlockWriteQueue();
		List<Integer> order = new ArrayList<>();

		for (int i = 0; i < 10; i++) {
			final int value = i;
			queue.submit(value, () -> order.add(value));
		}

		queue.drain(t -> { });

		for (int i = 0; i < 10; i++) {
			assertEquals(i, order.get(i), "deterministic order matters for replaying a save");
		}
	}

	@Test
	void submitAfterBarrierIsRejected() {
		BlockWriteQueue queue = new BlockWriteQueue();

		// draining inside a queued write simulates a straggler that missed the barrier
		queue.submit(1L, () -> assertThrows(IllegalStateException.class,
				() -> queue.submit(2L, () -> { }),
				"a write from a thread that missed the barrier must be refused, not applied later"));

		queue.drain(t -> { });
	}

	@Test
	void acceptingFlagFollowsTheBarrier() {
		BlockWriteQueue queue = new BlockWriteQueue();

		assertTrue(queue.isAccepting());
		queue.drain(t -> { });
		assertTrue(queue.isAccepting(), "the queue must accept work again next tick");
	}

	@Test
	void concurrentSubmitsAllReachTheBarrier() throws Exception {
		BlockWriteQueue queue = new BlockWriteQueue();
		AtomicInteger applied = new AtomicInteger();
		int threads = 8;
		int perThread = 40;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		try {
			for (int t = 0; t < threads; t++) {
				final int id = t;
				pool.execute(() -> {
					try {
						start.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					for (int i = 0; i < perThread; i++) {
						// distinct positions, so nothing may be collapsed away
						queue.submit((long) id * 10_000 + i, applied::incrementAndGet);
					}
				});
			}

			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

			queue.drain(t -> { });

			assertEquals(threads * perThread, applied.get(),
					"every distinct write must survive the barrier exactly once");
		} finally {
			pool.shutdownNow();
		}
	}

	// ------------------------------------------------------------ block entity safety

	@Test
	void hoppersAreNeverParallel() {
		assertFalse(BlockEntitySafety.isSafe("minecraft:hopper"),
				"a hopper reads the block above it; parallel ticking it duplicates items");
	}

	@Test
	void redstoneComponentsAreNeverParallel() {
		for (String type : List.of("minecraft:comparator", "minecraft:piston", "minecraft:observer")) {
			assertFalse(BlockEntitySafety.isSafe(type), type + " depends on vanilla tick order");
		}
	}

	@Test
	void plainVanillaBlockEntitiesAreParallel() {
		assertTrue(BlockEntitySafety.isSafe("minecraft:furnace"));
		assertTrue(BlockEntitySafety.isSafe("minecraft:sign"));
		assertTrue(BlockEntitySafety.isSafe("minecraft:chest"));
	}

	@Test
	void unknownModTypesFailClosed() {
		assertFalse(BlockEntitySafety.isSafe("somemod:mystery_ticker"),
				"an unknown mod block entity is unsafe until it says otherwise");
		assertFalse(BlockEntitySafety.isSafe(null));
	}

	@Test
	void modsCanOptInAndOptOut() {
		BlockEntitySafety.registerSafe("testmod:fast_furnace");
		assertTrue(BlockEntitySafety.isSafe("testmod:fast_furnace"));

		BlockEntitySafety.registerUnsafe("minecraft:furnace");
		assertFalse(BlockEntitySafety.isSafe("minecraft:furnace"),
				"an explicit unsafe registration must override the default");
	}

	@Test
	void partitionKeepsBothHalvesInOrder() {
		List<String> types = List.of(
				"minecraft:furnace",   // safe
				"minecraft:hopper",    // unsafe
				"minecraft:sign",      // safe
				"minecraft:piston"     // unsafe
		);

		BlockEntitySafety.Partition partition = BlockEntitySafety.partition(types);

		assertEquals(2, partition.parallel().length);
		assertEquals(2, partition.sequential().length);

		// indices, not values: the caller uses them to index its own ticker list
		assertEquals(0, partition.parallel()[0]);
		assertEquals(2, partition.parallel()[1]);
		assertEquals(1, partition.sequential()[0]);
		assertEquals(3, partition.sequential()[1]);
	}

	@Test
	void singleSafeTickerIsNotWorthParallelising() {
		assertFalse(BlockEntitySafety.partition(List.of("minecraft:furnace")).worthParallelising(),
				"one ticker has nothing to overlap with");
	}

	@Test
	void allUnsafeNeedsNoParallelPath() {
		BlockEntitySafety.Partition partition = BlockEntitySafety.partition(
				List.of("minecraft:hopper", "minecraft:piston"));

		assertEquals(0, partition.parallel().length);
		assertFalse(partition.worthParallelising());
	}
}
