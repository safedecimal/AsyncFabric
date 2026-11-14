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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps entities that can affect each other on the same thread.
 *
 * <p>Two entities in different chunks are not independent if one rides the other, holds its leash
 * or can reach it. Ticking them on different threads is how a horse and its rider get different
 * positions for a tick, how a leash unlinks, or how a vehicle gets ticked after the passenger that
 * was supposed to be moved by it. All of those surface as dupes, ghost entities or crashes far from
 * the cause.
 *
 * <p>So before any region is scheduled, every such relation is recorded, and regions are merged
 * with a union-find until no connected group spans two regions. The result is a set of disjoint
 * groups, each of which one thread ticks as a unit. Merging is transitive: A rides B, B rides C
 * means A, B and C share a thread even if they sit in three different areas.
 *
 * <p>This runs on the game thread, over data the mixin collected, so it is single-threaded and
 * needs no locking.
 */
public final class EntityGroups {
	private final int[] parent;
	private final int[] rank;

	/**
	 * @param entityCount number of entities participating in this tick
	 */
	public EntityGroups(int entityCount) {
		this.parent = new int[entityCount];
		this.rank = new int[entityCount];

		for (int i = 0; i < entityCount; i++) {
			parent[i] = i;
		}
	}

	/**
	 * Records that two entities must tick together.
	 *
	 * @param a first entity index
	 * @param b second entity index
	 */
	public void link(int a, int b) {
		int rootA = find(a);
		int rootB = find(b);

		if (rootA == rootB) return;

		// union by rank keeps the trees shallow, so find stays near-constant even with long chains
		if (rank[rootA] < rank[rootB]) {
			parent[rootA] = rootB;
		} else if (rank[rootA] > rank[rootB]) {
			parent[rootB] = rootA;
		} else {
			parent[rootB] = rootA;
			rank[rootA]++;
		}
	}

	/**
	 * @param index entity index
	 * @return the group root of that entity
	 */
	public int find(int index) {
		int root = index;

		while (parent[root] != root) {
			root = parent[root];
		}

		// path compression: flatten on the way back
		int current = index;

		while (parent[current] != root) {
			int next = parent[current];
			parent[current] = root;
			current = next;
		}

		return root;
	}

	/**
	 * @param indices entity indices to group
	 * @return groups of indices, where each group must be ticked by one thread
	 */
	public List<int[]> groupsOf(int[] indices) {
		Map<Integer, List<Integer>> byRoot = new HashMap<>();

		for (int index : indices) {
			byRoot.computeIfAbsent(find(index), ignored -> new ArrayList<>()).add(index);
		}

		List<int[]> groups = new ArrayList<>(byRoot.size());

		for (List<Integer> members : byRoot.values()) {
			int[] group = new int[members.size()];

			for (int i = 0; i < group.length; i++) {
				group[i] = members.get(i);
			}

			// stable ordering inside a group keeps the tick order reproducible
			Arrays.sort(group);
			groups.add(group);
		}

		// stable ordering of groups too: determinism is what makes deferred effects replayable
		groups.sort((left, right) -> Integer.compare(left[0], right[0]));

		return groups;
	}

	/**
	 * @return {@code true} if the two entities ended up in the same group
	 */
	public boolean sameGroup(int a, int b) {
		return find(a) == find(b);
	}
}
