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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.asyncfabric.impl.async.world.TickPlan;

/**
 * Tests the planning step of parallel world ticking, where a mistake corrupts a save.
 *
 * <p>{@link TickPlan} is deliberately free of Minecraft types so these assertions can run without
 * a game. The three properties checked here are the whole safety argument for scheduling:
 * completeness (nothing dropped, nothing doubled), interaction closure (a vehicle never leaves its
 * rider), and region exclusivity (a region is only ever touched by one thread).
 */
class TickPlanTest {
	private static int[] chunks(int... values) {
		return values;
	}

	private static List<int[]> links(int[]... pairs) {
		List<int[]> list = new ArrayList<>();

		for (int[] pair : pairs) {
			list.add(pair);
		}

		return list;
	}

	@Test
	void everyEntityIsScheduledExactlyOnce() {
		// 10 entities spread over many chunks
		int[] xs = { 0, 4, 8, 12, 16, 20, 24, 28, 32, 36 };
		int[] zs = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

		TickPlan plan = TickPlan.build(xs, zs, List.of(), 2);

		assertEquals(10, plan.entityCount(), "the plan must cover every entity");

		Set<Integer> seen = new HashSet<>();

		for (TickPlan.Unit unit : plan.units()) {
			for (int index : unit.entities()) {
				assertTrue(seen.add(index), "entity " + index + " was scheduled twice — that is a dupe");
			}
		}

		assertEquals(10, seen.size());
	}

	@Test
	void linkedEntitiesShareAUnitEvenAcrossRegions() {
		// index 0 at chunk 0, index 1 far away at chunk 200: different regions, but must co-tick
		int[] xs = { 0, 200 };
		int[] zs = { 0, 0 };

		TickPlan plan = TickPlan.build(xs, zs, links(new int[] { 0, 1 }), 2);

		assertEquals(1, plan.units().size(), "a linked pair must be one unit, or they race");

		int[] entities = plan.units().get(0).entities();
		assertEquals(2, entities.length);
	}

	@Test
	void linksAreTransitive() {
		// 0 rides 1, 1 rides 2, all far apart
		int[] xs = { 0, 100, 200 };
		int[] zs = { 0, 0, 0 };

		TickPlan plan = TickPlan.build(xs, zs, links(new int[] { 0, 1 }, new int[] { 1, 2 }), 2);

		assertEquals(1, plan.units().size(), "a passenger chain must not split across threads");
		assertEquals(3, plan.entityCount());
	}

	@Test
	void aUnitNeverSpansTwoRegions() {
		int[] xs = new int[40];
		int[] zs = new int[40];

		for (int i = 0; i < 40; i++) {
			xs[i] = i * 3;
			zs[i] = i * 3;
		}

		TickPlan plan = TickPlan.build(xs, zs, List.of(), 2);

		Set<Integer> regions = new HashSet<>();

		for (TickPlan.Unit unit : plan.units()) {
			assertTrue(regions.add(unit.region()), "two units claimed region " + unit.region()
					+ " — ownership would not be exclusive");

			// every entity of the unit must map to that same region
			for (int index : unit.entities()) {
				assertEquals(unit.region(), plan.entityRegion()[index],
						"unit spans regions, so two threads could touch one region");
			}
		}
	}

	@Test
	void schedulingIsDeterministic() {
		int[] xs = { 7, 3, 11, 5 };
		int[] zs = { 7, 3, 11, 5 };

		TickPlan first = TickPlan.build(xs, zs, links(new int[] { 0, 2 }), 2);
		TickPlan second = TickPlan.build(xs, zs, links(new int[] { 2, 0 }), 2);

		assertEquals(first.units().size(), second.units().size());

		for (int i = 0; i < first.units().size(); i++) {
			assertEquals(first.units().get(i).region(), second.units().get(i).region(),
					"region order must not depend on link direction");
			assertArrayEquals(first.units().get(i).entities(), second.units().get(i).entities());
		}
	}

	@Test
	void emptySetProducesNoUnits() {
		TickPlan plan = TickPlan.build(new int[0], new int[0], List.of(), 2);

		assertTrue(plan.units().isEmpty());
		assertEquals(0, plan.entityCount());
	}

	@Test
	void singleEntityIsOneUnit() {
		TickPlan plan = TickPlan.build(chunks(5), chunks(5), List.of(), 2);

		assertEquals(1, plan.units().size());
		assertEquals(1, plan.entityCount());
	}

	@Test
	void outOfRangeLinksAreIgnored() {
		// a link to an entity index that does not exist must not throw or corrupt the plan
		int[] xs = { 0, 8 };
		int[] zs = { 0, 8 };

		TickPlan plan = TickPlan.build(xs, zs, links(new int[] { 0, 99 }, new int[] { -1, 1 }), 2);

		assertEquals(2, plan.entityCount(), "both real entities must still be scheduled");
	}

	@Test
	void denseAreaCollapsesToFewUnits() {
		// all entities in one region: they all share a thread, which is correct, not a bug
		int[] xs = { 0, 1, 1, 0, 1 };
		int[] zs = { 0, 0, 1, 1, 0 };

		TickPlan plan = TickPlan.build(xs, zs, List.of(), 2);

		assertEquals(1, plan.units().size());
		assertEquals(5, plan.entityCount());
	}

	@Test
	void spreadAreaProducesSeveralUnits() {
		int[] xs = new int[16];
		int[] zs = new int[16];

		for (int i = 0; i < 16; i++) {
			xs[i] = i * 4;
			zs[i] = 0;
		}

		TickPlan plan = TickPlan.build(xs, zs, List.of(), 2);

		assertFalse(plan.units().isEmpty());
		assertTrue(plan.units().size() > 1, "a spread-out entity set must actually parallelise");
		assertEquals(16, plan.entityCount());
	}

	private static void assertArrayEquals(int[] expected, int[] actual) {
		assertEquals(expected.length, actual.length);

		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i], "element " + i);
		}
	}
}
