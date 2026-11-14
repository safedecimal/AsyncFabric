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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import net.asyncfabric.impl.async.AsyncSchedulerImpl;

/**
 * Ticks the entities of one level in parallel, over disjoint regions.
 *
 * <p>This is the only place where Minecraft world state is touched from more than one thread, so
 * the whole safety argument lives here. The sequence is:
 *
 * <ol>
 *   <li>Collect the entities vanilla would tick, on the game thread. No entity state is read
 *   beyond position and vehicle links.</li>
 *   <li>Union entities that can affect each other, so a vehicle and its passengers land in one
 *   group regardless of position.</li>
 *   <li>Assign each group to a region of the {@link RegionGrid} and order the regions. Two groups
 *   in the same region share a thread; a region is never split.</li>
 *   <li>Claim each region for exactly one thread and tick it there.</li>
 *   <li>Wait for every region, drain {@link DeferredActions} on the game thread, and then run the
 *   level's own post-entity work.</li>
 * </ol>
 *
 * <p>Any failure — an exception, a timeout, an ownership violation — abandons the parallel result
 * and reports it through {@link RaceGuard}. The caller then runs vanilla's sequential tick, so the
 * world is never left half-ticked.
 */
public final class ParallelLevelTick {
	private static final long REGION_TIMEOUT_SECONDS = 30;

	private final RaceGuard guard;

	public ParallelLevelTick(RaceGuard guard) {
		this.guard = guard;
	}

	/**
	 * Runs one parallel entity tick.
	 *
	 * @param level the level to tick
	 * @param entities the entities vanilla would pass to the ticker, in vanilla order
	 * @param vanillaTick vanilla's own per-entity consumer, reused unchanged so its despawn,
	 *                    distance, vehicle and profiler behaviour all still apply
	 * @param deferred cross-region effects collected during this tick
	 * @param failures receives per-entity failures; they are not fatal, matching vanilla where one
	 *                 broken entity does not stop the rest
	 * @return how far the tick got; the caller decides whether replaying is safe
	 */
	public TickOutcome tickEntities(ServerLevel level, List<Entity> entities, Consumer<Entity> vanillaTick,
			DeferredActions deferred, Consumer<Throwable> failures) {
		if (entities.isEmpty()) return TickOutcome.COMPLETED;

		if (!guard.isParallelEnabled()) {
			return TickOutcome.SKIPPED;
		}

		int count = entities.size();
		int[] chunkXs = new int[count];
		int[] chunkZs = new int[count];
		Map<Entity, Integer> index = new IdentityHashMap<>(count * 2);

		for (int i = 0; i < count; i++) {
			Entity entity = entities.get(i);
			index.put(entity, i);

			long packed = entity.chunkPosition().pack();
			chunkXs[i] = net.minecraft.world.level.ChunkPos.getX(packed);
			chunkZs[i] = net.minecraft.world.level.ChunkPos.getZ(packed);
		}

		// The planning itself is pure and unit tested; this only feeds it the interaction links.
		TickPlan plan = TickPlan.build(chunkXs, chunkZs, interactionLinks(entities, index),
				RegionGrid.defaultRegionSize());
		List<TickPlan.Unit> units = plan.units();

		int[] bounds = chunkBounds(chunkXs, chunkZs);
		RegionGrid grid = new RegionGrid(bounds[0], bounds[1], bounds[2], bounds[3], RegionGrid.defaultRegionSize());

		List<Integer> regions = new ArrayList<>(units.size());

		for (TickPlan.Unit unit : units) {
			regions.add(unit.region());
		}

		List<CompletableFuture<Void>> running = new ArrayList<>(regions.size());
		List<Integer> claimed = new ArrayList<>(regions.size());
		Throwable firstFailure = null;

		java.util.concurrent.Executor pool = AsyncSchedulerImpl.executor();

		// Claim every region BEFORE ticking anything. If one cannot be claimed we abandon the run
		// with nothing advanced, so replaying the vanilla tick is safe. Claiming lazily and
		// aborting midway would leave some entities ticked and some not.
		for (int region : regions) {
			if (!grid.claim(region, region + 1)) {
				for (int taken : claimed) {
					grid.release(taken, taken + 1);
				}

				guard.recordFallback(gameTime(level),
						new IllegalStateException("region " + region + " was already claimed"));

				return TickOutcome.SKIPPED;
			}

			claimed.add(region);
		}

		for (TickPlan.Unit unit : units) {
			int region = unit.region();
			int finalOwner = region + 1;
			int[] indices = unit.entities();

			running.add(CompletableFuture.runAsync(() -> {
				for (int entityIndex : indices) {
					Entity entity = entities.get(entityIndex);

					try {
						// vanilla's own per-entity logic, unchanged
						vanillaTick.accept(entity);
					} catch (Throwable t) {
						failures.accept(t);
					}
				}
			}, pool).whenComplete((ignored, throwable) -> grid.release(region, finalOwner)));
		}

		if (firstFailure == null) {
			for (CompletableFuture<Void> task : running) {
				try {
					task.get(REGION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
				} catch (ExecutionException e) {
					if (firstFailure == null) firstFailure = e.getCause() != null ? e.getCause() : e;
				} catch (java.util.concurrent.TimeoutException e) {
					if (firstFailure == null) {
						firstFailure = new IllegalStateException("parallel entity tick did not finish within "
								+ REGION_TIMEOUT_SECONDS + "s");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();

					if (firstFailure == null) firstFailure = e;
				}
			}
		}

		if (firstFailure != null) {
			// entities already advanced; the caller must NOT replay or they would tick twice
			guard.recordFallback(gameTime(level), firstFailure);

			return TickOutcome.PARTIAL;
		}

		// cross-region effects apply on the game thread, in queue order
		deferred.drain(failures);

		guard.recordParallelSuccess(gameTime(level));

		return TickOutcome.COMPLETED;
	}

	/**
	 * Collects the entity pairs that must be ticked by one thread.
	 *
	 * <p>Covers the relations vanilla's own tick loop walks: vehicle↔passenger, including chains
	 * via the root vehicle. A pair whose counterpart is outside the ticked set is skipped, which is
	 * correct — that entity belongs to another level and is not being scheduled here.
	 */
	private static List<int[]> interactionLinks(List<Entity> entities, Map<Entity, Integer> index) {
		List<int[]> links = new ArrayList<>();

		for (int i = 0; i < entities.size(); i++) {
			Entity entity = entities.get(i);
			addLink(links, i, entity.getVehicle(), index);

			for (Entity passenger : entity.getPassengers()) {
				addLink(links, i, passenger, index);
			}

			addLink(links, i, entity.getRootVehicle(), index);
		}

		return links;
	}

	private static void addLink(List<int[]> links, int fromIndex, Entity to, Map<Entity, Integer> index) {
		if (to == null) return;

		Integer toIndex = index.get(to);

		if (toIndex == null || toIndex == fromIndex) return;

		links.add(new int[] { fromIndex, toIndex });
	}

	/**
	 * @return {@code {minChunkX, minChunkZ, maxChunkX, maxChunkZ}} covering the entity set
	 */
	private static int[] chunkBounds(int[] chunkXs, int[] chunkZs) {
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

		return new int[] { minX, minZ, maxX, maxZ };
	}

	/**
	 * @param level the level being ticked, may be {@code null} in tests
	 * @return the level's game time, or {@code 0} when unavailable
	 */
	private static long gameTime(ServerLevel level) {
		return level != null ? level.getGameTime() : 0L;
	}

	/** @return the guard used by this ticker */
	public RaceGuard guard() {
		return guard;
	}

	/** @return the thread id used for the game thread when claiming regions */
	static int mainThreadId() {
		return (int) Thread.currentThread().getId();
	}
}
