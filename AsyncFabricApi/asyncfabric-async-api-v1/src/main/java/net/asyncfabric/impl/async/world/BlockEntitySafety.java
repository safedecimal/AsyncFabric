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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which block entities may tick off the game thread.
 *
 * <p>Most block entities only touch their own block: a furnace smelts, a sign does nothing, a
 * spawner counts down. Those are safe to run in parallel. A few read or write neighbouring blocks,
 * and vanilla's ordering assumption is that they run one at a time — a hopper pulling from the
 * chest above it, a comparator reading its input, a piston moving a block.
 *
 * <p>The decision is made by type name, because that is what {@code TickingBlockEntity} exposes and
 * because a mod's block entity is simply "unknown" until a modder says otherwise. Unknown is treated
 * as <em>unsafe</em>: a wrong "safe" verdict corrupts a save, a wrong "unsafe" verdict only costs
 * speed.
 */
public final class BlockEntitySafety {
	/**
	 * Types that read or write neighbouring blocks, or whose behaviour depends on being ticked in
	 * vanilla's order. Matched by simple name, so a mod reusing one of these names is covered too.
	 */
	private static final Set<String> UNSAFE_TYPES = Set.of(
			// containers that pull from neighbours
			"minecraft:hopper",
			// redstone components: order defines behaviour
			"minecraft:comparator",
			"minecraft:piston",
			"minecraft:sticky_piston",
			"minecraft:moving_piston",
			// observers and detectors read neighbours
			"minecraft:observer",
			"minecraft:sculk_sensor",
			"minecraft:calibrated_sculk_sensor",
			"minecraft:daylight_detector",
			"minecraft:target",
			// blocks that spread or convert neighbours
			"minecraft:beacon",
			"minecraft:conduit",
			"minecraft:lectern"
	);

	/** Type names known to be safe; extended as mods opt in, see {@link #registerSafe}. */
	private static final Set<String> SAFE_TYPES = ConcurrentHashMap.newKeySet();

	/** Types a mod explicitly declared unsafe, in addition to the built-in list. */
	private static final Set<String> UNSAFE_REGISTERED = ConcurrentHashMap.newKeySet();

	private BlockEntitySafety() {
	}

	/**
	 * Declares a block entity type safe to tick off the game thread.
	 *
	 * <p>Only correct if the ticker never reads or writes a block outside its own position. Getting
	 * that wrong is a save-corrupting race, so this is opt-in per mod.
	 *
	 * @param type the type id, e.g. {@code "mymod:fast_furnace"}
	 */
	public static void registerSafe(String type) {
		SAFE_TYPES.add(type);
	}

	/**
	 * Declares a block entity type unsafe, overriding any default verdict.
	 *
	 * @param type the type id
	 */
	public static void registerUnsafe(String type) {
		UNSAFE_REGISTERED.add(type);
	}

	/**
	 * Clears every registration made through {@link #registerSafe} and {@link #registerUnsafe}.
	 *
	 * <p>Exists so a test's registrations cannot leak into the next one; production code has no
	 * reason to call it.
	 */
	public static void resetRegistrations() {
		SAFE_TYPES.clear();
		UNSAFE_REGISTERED.clear();
	}

	/**
	 * @param type the block entity type id
	 * @return {@code true} if its ticker may run off the game thread
	 */
	public static boolean isSafe(String type) {
		if (type == null) return false;
		if (UNSAFE_REGISTERED.contains(type)) return false;
		if (SAFE_TYPES.contains(type)) return true;

		// a vanilla type outside the deny-list only mutates its own block, so it is safe.
		// a mod type is unknown behaviour: unsafe until the mod says otherwise.
		return type.startsWith("minecraft:") && !UNSAFE_TYPES.contains(type);
	}

	/**
	 * Splits ticker indices into the part that may run in parallel and the part that must stay on
	 * the game thread, preserving order inside each part.
	 *
	 * @param types the type id of each ticker, in tick order
	 * @return the partition
	 */
	public static Partition partition(List<String> types) {
		int safeCount = 0;

		for (String type : types) {
			if (isSafe(type)) safeCount++;
		}

		int[] parallel = new int[safeCount];
		int[] sequential = new int[types.size() - safeCount];
		int p = 0;
		int s = 0;

		for (int i = 0; i < types.size(); i++) {
			if (isSafe(types.get(i))) {
				parallel[p++] = i;
			} else {
				sequential[s++] = i;
			}
		}

		return new Partition(parallel, sequential);
	}

	/** Indices of the tickers that may run in parallel, and those that may not. */
	public static final class Partition {
		private final int[] parallel;
		private final int[] sequential;

		Partition(int[] parallel, int[] sequential) {
			this.parallel = parallel;
			this.sequential = sequential;
		}

		/** @return indices safe to tick off-thread */
		public int[] parallel() {
			return parallel;
		}

		/** @return indices that must tick on the game thread, in vanilla order */
		public int[] sequential() {
			return sequential;
		}

		/** @return {@code true} if parallelising is worth the bookkeeping */
		public boolean worthParallelising() {
			return parallel.length > 1;
		}
	}
}
