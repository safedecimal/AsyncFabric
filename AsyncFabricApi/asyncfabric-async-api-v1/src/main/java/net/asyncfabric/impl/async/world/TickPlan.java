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
import java.util.TreeMap;

/**
 * Works out which thread ticks which entity, with no Minecraft types involved.
 *
 * <p>This is the part of parallel world ticking where a mistake means a corrupted save, so it is
 * deliberately pure: it takes chunk coordinates and interaction links, and returns a plan. That
 * makes it testable without booting a game, which matters because the alternative — trusting a
 * glue class that can only be exercised in a live world — is how races ship.
 *
 * <p>The plan satisfies three properties:
 *
 * <ul>
 *   <li>every entity appears in exactly one unit;</li>
 *   <li>entities that interact (vehicle, passenger, leash) are in the same unit, transitively;</li>
 *   <li>a unit never spans two regions, so a region is always owned by one thread.</li>
 * </ul>
 */
public final class TickPlan {
	private final int[] entityRegion;
	private final List<Unit> units;

	private TickPlan(int[] entityRegion, List<Unit> units) {
		this.entityRegion = entityRegion;
		this.units = units;
	}

	/**
	 * Builds a plan.
	 *
	 * @param chunkXs chunk x of each entity, indexed like the entity list
	 * @param chunkZs chunk z of each entity
	 * @param links pairs of entity indices that must tick together
	 * @param regionSize chunks per region side
	 * @return the plan
	 */
	public static TickPlan build(int[] chunkXs, int[] chunkZs, List<int[]> links, int regionSize) {
		int count = chunkXs.length;
		int[] regionOf = new int[count];

		if (count == 0) {
			return new TickPlan(regionOf, List.of());
		}

		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int i = 0; i < count; i++) {
			minX = Math.min(minX, chunkXs[i]);
			minZ = Math.min(minZ, chunkZs[i]);
			maxX = Math.max(maxX, chunkXs[i]);
			maxZ = Math.max(maxZ, chunkZs[i]);
		}

		RegionGrid grid = new RegionGrid(minX, minZ, maxX, maxZ, regionSize);

		for (int i = 0; i < count; i++) {
			regionOf[i] = grid.regionOf(chunkXs[i], chunkZs[i]);
		}

		EntityGroups groups = new EntityGroups(count);

		// interaction links first: a vehicle must stay with its passengers wherever they are
		for (int[] link : links) {
			int a = link[0];
			int b = link[1];

			if (a >= 0 && a < count && b >= 0 && b < count) {
				groups.link(a, b);
			}
		}

		// then region links: two entities owned by the same region share a thread anyway
		int[] firstInRegion = new int[grid.regionCount()];
		java.util.Arrays.fill(firstInRegion, -1);

		for (int i = 0; i < count; i++) {
			int region = regionOf[i];

			if (firstInRegion[region] < 0) {
				firstInRegion[region] = i;
			} else {
				groups.link(firstInRegion[region], i);
			}
		}

		int[] all = new int[count];

		for (int i = 0; i < count; i++) {
			all[i] = i;
		}

		// grouped by region in ascending order so the schedule is deterministic
		TreeMap<Integer, List<int[]>> byRegion = new TreeMap<>();

		for (int[] group : groups.groupsOf(all)) {
			byRegion.computeIfAbsent(regionOf[group[0]], ignored -> new ArrayList<>()).add(group);
		}

		List<Unit> units = new ArrayList<>(byRegion.size());

		for (java.util.Map.Entry<Integer, List<int[]>> entry : byRegion.entrySet()) {
			units.add(new Unit(entry.getKey(), entry.getValue()));
		}

		return new TickPlan(regionOf, List.copyOf(units));
	}

	/** @return the region each entity belongs to */
	public int[] entityRegion() {
		return entityRegion;
	}

	/** @return one unit per thread, ordered by region */
	public List<Unit> units() {
		return units;
	}

	/** @return total entities covered; the caller checks this equals the entity count */
	public int entityCount() {
		int total = 0;

		for (Unit unit : units) {
			for (int[] group : unit.groups) {
				total += group.length;
			}
		}

		return total;
	}

	/** Entities one thread ticks, all in the same region. */
	public static final class Unit {
		private final int region;
		private final List<int[]> groups;

		Unit(int region, List<int[]> groups) {
			this.region = region;
			this.groups = groups;
		}

		/** @return the region this unit owns */
		public int region() {
			return region;
		}

		/** @return entity index groups, each already internally ordered */
		public List<int[]> groups() {
			return groups;
		}

		/** @return every entity index in this unit, in tick order */
		public int[] entities() {
			int size = 0;

			for (int[] group : groups) {
				size += group.length;
			}

			int[] flat = new int[size];
			int index = 0;

			for (int[] group : groups) {
				System.arraycopy(group, 0, flat, index, group.length);
				index += group.length;
			}

			return flat;
		}
	}
}
