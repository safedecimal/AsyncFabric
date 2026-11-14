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

/**
 * Says, for the current thread, which region it is allowed to mutate.
 *
 * <p>Without this a worker cannot tell whether the block it wants to write belongs to its own
 * region or to somebody else's, and every write would have to be queued. With it the common case
 * stays direct: a random tick that changes a block in its own region writes immediately, exactly as
 * vanilla would, and only cross-region writes are deferred.
 *
 * <p>Absent context means "not on a worker" — that is the game thread, which owns everything and
 * must never be redirected through a queue.
 */
public final class RegionContext {
	private static final ThreadLocal<RegionContext> CURRENT = new ThreadLocal<>();

	private final TickPlan plan;
	private final int unitIndex;
	private final RegionGrid grid;

	private RegionContext(TickPlan plan, int unitIndex, RegionGrid grid) {
		this.plan = plan;
		this.unitIndex = unitIndex;
		this.grid = grid;
	}

	/**
	 * Installs a context on the calling thread for the duration of a region tick.
	 *
	 * @param plan the current tick plan, used to test whether a position is owned
	 * @param unitIndex index of the unit this thread is ticking
	 * @param grid the region grid the plan was built from
	 * @return the previous context, to be restored with {@link #restore}
	 */
	public static RegionContext push(TickPlan plan, int unitIndex, RegionGrid grid) {
		RegionContext previous = CURRENT.get();
		CURRENT.set(new RegionContext(plan, unitIndex, grid));

		return previous;
	}

	/**
	 * Restores the context that was active before {@link #push}.
	 *
	 * @param previous the value returned by {@code push}, may be {@code null}
	 */
	public static void restore(RegionContext previous) {
		if (previous == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(previous);
		}
	}

	/** @return the calling thread's context, or {@code null} on the game thread */
	public static RegionContext current() {
		return CURRENT.get();
	}

	/**
	 * @param chunkX chunk x of the position being written
	 * @param chunkZ chunk z of the position being written
	 * @return {@code true} if the calling thread owns that chunk and may write immediately
	 */
	public boolean owns(int chunkX, int chunkZ) {
		int region = grid.regionOf(chunkX, chunkZ);

		if (region != plan.units().get(unitIndex).region()) {
			// a different region: the write must be deferred to the barrier
			return false;
		}

		return true;
	}

	/** @return the region this thread owns */
	public int region() {
		return plan.units().get(unitIndex).region();
	}
}
