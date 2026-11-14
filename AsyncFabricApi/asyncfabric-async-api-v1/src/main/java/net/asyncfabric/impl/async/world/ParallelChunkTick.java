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

package net.asyncfabric.impl.async.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import net.asyncfabric.impl.async.AsyncSchedulerImpl;

/**
 * Ticks randomly-ticking chunks and their block entities in parallel, over disjoint regions.
 *
 * <p>This is the second parallel surface, after entities. It is deliberately narrower:
 *
 * <ul>
 *   <li>Block and fluid random ticks run in parallel, because they mutate one block and vanilla
 *   already tolerates them being applied in any order within a tick.</li>
 *   <li>Block entities run in parallel only when {@link BlockEntitySafety} says their ticker
 *   touches nothing outside its own block. Hoppers, comparators, pistons and unknown mod types stay
 *   on the game thread, in vanilla order, before the parallel part starts.</li>
 *   <li>Redstone and neighbour updates stay sequential. Their behaviour <em>is</em> the order.</li>
 * </ul>
 *
 * <p>Writes that cross a region boundary are queued in {@link BlockWriteQueue} and applied at the
 * barrier on the game thread, which is what keeps two workers from writing one block at once.
 */
public final class ParallelChunkTick {
	private static final long TIMEOUT_SECONDS = 30;

	private final RaceGuard guard;

	public ParallelChunkTick(RaceGuard guard) {
		this.guard = guard;
	}

	/**
	 * Ticks block entities off the game thread where that is safe.
	 *
	 * <p>The unsafe ones must already have run on the game thread before this is called; the caller
	 * splits the list with {@link BlockEntitySafety#partition}.
	 *
	 * @param plan the tick plan, used for region ownership
	 * @param grid the region grid
	 * @param chunkXs chunk x of every tickable block entity, in tick order
	 * @param chunkZs chunk z of every tickable block entity
	 * @param safeIndices which entries of the list may run in parallel
	 * @param ticker runs one block entity tick
	 * @param writes receives cross-region block writes
	 * @param failures receives per-tick failures
	 * @return how far the tick got
	 */
	public TickOutcome tickBlockEntities(TickPlan plan, RegionGrid grid, int[] chunkXs, int[] chunkZs,
			int[] safeIndices, IntConsumer ticker, BlockWriteQueue writes, java.util.function.Consumer<Throwable> failures) {
		if (safeIndices.length < 2) {
			// nothing to overlap; the game thread handles this cheaper
			return TickOutcome.SKIPPED;
		}

		if (!guard.isParallelEnabled()) {
			return TickOutcome.SKIPPED;
		}

		return run(plan, grid, chunkXs, chunkZs, safeIndices, writes, failures, index -> ticker.accept(index),
				"block entities");
	}

	/**
	 * Ticks randomly-ticking chunks off the game thread.
	 *
	 * @param plan the tick plan, used for region ownership
	 * @param grid the region grid
	 * @param chunkXs chunk x of every tickable chunk
	 * @param chunkZs chunk z of every tickable chunk
	 * @param ticker runs one chunk's random ticks
	 * @param writes receives cross-region block writes
	 * @param failures receives per-chunk failures
	 * @return how far the tick got
	 */
	public TickOutcome tickChunks(TickPlan plan, RegionGrid grid, int[] chunkXs, int[] chunkZs,
			IntConsumer ticker, BlockWriteQueue writes, java.util.function.Consumer<Throwable> failures) {
		if (chunkXs.length < 2 || !guard.isParallelEnabled()) {
			return TickOutcome.SKIPPED;
		}

		int[] all = new int[chunkXs.length];

		for (int i = 0; i < all.length; i++) {
			all[i] = i;
		}

		return run(plan, grid, chunkXs, chunkZs, all, writes, failures, ticker, "chunks");
	}

	private TickOutcome run(TickPlan plan, RegionGrid grid, int[] chunkXs, int[] chunkZs, int[] indices,
			BlockWriteQueue writes, java.util.function.Consumer<Throwable> failures, IntConsumer ticker,
			String what) {
		// group the work by the region that owns each chunk, so a region is only ever ticked by
		// one thread
		java.util.TreeMap<Integer, List<Integer>> byRegion = new java.util.TreeMap<>();

		for (int index : indices) {
			int region = grid.regionOf(chunkXs[index], chunkZs[index]);
			byRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(index);
		}

		List<Integer> regions = new ArrayList<>(byRegion.keySet());
		List<Integer> claimed = new ArrayList<>(regions.size());

		// claim everything first: an abort then means nothing ran, so replaying is safe
		for (int region : regions) {
			if (!grid.claim(region, region + 1)) {
				for (int taken : claimed) {
					grid.release(taken, taken + 1);
				}

				guard.recordFallback(0, new IllegalStateException(what + " region " + region + " already claimed"));

				return TickOutcome.SKIPPED;
			}

			claimed.add(region);
		}

		java.util.concurrent.Executor pool = AsyncSchedulerImpl.executor();
		List<CompletableFuture<Void>> running = new ArrayList<>(regions.size());

		for (int region : regions) {
			List<Integer> work = byRegion.get(region);
			int finalOwner = region + 1;
			int unitIndex = unitIndexFor(plan, region);

			running.add(CompletableFuture.runAsync(() -> {
				RegionContext previous = unitIndex >= 0
						? RegionContext.push(plan, unitIndex, grid)
						: null;

				try {
					for (int index : work) {
						try {
							ticker.accept(index);
						} catch (Throwable t) {
							failures.accept(t);
						}
					}
				} finally {
					RegionContext.restore(previous);
				}
			}, pool).whenComplete((ignored, throwable) -> grid.release(region, finalOwner)));
		}

		Throwable firstFailure = null;

		for (CompletableFuture<Void> task : running) {
			try {
				task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				if (firstFailure == null) firstFailure = e.getCause() != null ? e.getCause() : e;
			} catch (java.util.concurrent.TimeoutException e) {
				if (firstFailure == null) {
					firstFailure = new IllegalStateException("parallel " + what + " tick did not finish within "
							+ TIMEOUT_SECONDS + "s");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();

				if (firstFailure == null) firstFailure = e;
			}
		}

		if (firstFailure != null) {
			guard.recordFallback(0, firstFailure);

			return TickOutcome.PARTIAL;
		}

		// cross-region block changes apply on the game thread, collapsed per position
		writes.drain(failures);

		guard.recordParallelSuccess(0);

		return TickOutcome.COMPLETED;
	}

	/** @return the guard this ticker reports to */
	public RaceGuard guard() {
		return guard;
	}

	/** @return the plan unit whose region matches, or {@code -1} when the plan has no such unit */
	private static int unitIndexFor(TickPlan plan, int region) {
		List<TickPlan.Unit> units = plan.units();

		for (int i = 0; i < units.size(); i++) {
			if (units.get(i).region() == region) return i;
		}

		return -1;
	}
}
