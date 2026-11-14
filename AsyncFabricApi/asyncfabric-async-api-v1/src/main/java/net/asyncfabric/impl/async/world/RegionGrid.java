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

/**
 * Splits a level into square regions of chunks and hands each one to a single thread.
 *
 * <p>Chunks are the natural unit: an entity belongs to exactly one chunk, and vanilla's own
 * concurrency assumptions are per-chunk. Grouping several chunks into one region keeps the number
 * of tasks close to the number of workers without making every task a single cheap entity.
 *
 * <p>A region is the unit of exclusive ownership, not the chunk: two neighbouring chunks may share
 * a region, and then one thread ticks both — which is correct, just less parallel. Chunks are
 * therefore <em>never</em> split between threads.
 *
 * <p>Entities that can affect each other (a vehicle and its passengers, a leash holder and its
 * leashed entity) must land in the same region. That is not decided here — see
 * {@link EntityGroups}, which merges regions by connectivity before any of them is scheduled.
 */
public final class RegionGrid {
	/** Chunks per region side; 1 means one region per chunk. */
	public static final int DEFAULT_REGION_SIZE = 2;

	private final int regionSize;
	private final int[] owners;
	private final int width;
	private final int originX;
	private final int originZ;

	/**
	 * @param minChunkX lowest chunk x of the area
	 * @param minChunkZ lowest chunk z of the area
	 * @param maxChunkX highest chunk x of the area
	 * @param maxChunkZ highest chunk z of the area
	 * @param regionSize chunks per region side
	 */
	public RegionGrid(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int regionSize) {
		if (regionSize < 1) throw new IllegalArgumentException("regionSize must be >= 1, was " + regionSize);

		this.regionSize = regionSize;
		this.originX = minChunkX;
		this.originZ = minChunkZ;
		this.width = Math.max(1, ((maxChunkX - minChunkX) / regionSize) + 1);
		int height = Math.max(1, ((maxChunkZ - minChunkZ) / regionSize) + 1);

		this.owners = new int[width * height];
		java.util.Arrays.fill(owners, RegionOwnership.FREE);
	}

	/** @return region size in chunks used when none was configured */
	public static int defaultRegionSize() {
		return DEFAULT_REGION_SIZE;
	}

	/**
	 * @param chunkX chunk x
	 * @param chunkZ chunk z
	 * @return the region index owning that chunk
	 */
	public int regionOf(int chunkX, int chunkZ) {
		int rx = Math.floorDiv(chunkX - originX, regionSize);
		int rz = Math.floorDiv(chunkZ - originZ, regionSize);

		return index(rx, rz);
	}

	/** @return total number of regions */
	public int regionCount() {
		return owners.length;
	}

	/**
	 * Tries to claim {@code region} for {@code threadId}.
	 *
	 * @return {@code true} if the caller may tick it now
	 */
	public boolean claim(int region, int threadId) {
		synchronized (owners) {
			int current = owners[region];

			if (current == threadId) return true;
			if (current != RegionOwnership.FREE) return false;

			owners[region] = threadId;

			return true;
		}
	}

	/** Releases a region previously claimed by {@code threadId}. */
	public void release(int region, int threadId) {
		synchronized (owners) {
			if (owners[region] != threadId) {
				throw new IllegalStateException("region " + region + " released by thread " + threadId
						+ " but owned by " + owners[region]);
			}

			owners[region] = RegionOwnership.FREE;
		}
	}

	/** @return {@code true} if {@code region} is currently claimed */
	public boolean isClaimed(int region) {
		synchronized (owners) {
			return owners[region] != RegionOwnership.FREE;
		}
	}

	/** @return the thread owning {@code region}, or {@link RegionOwnership#FREE} */
	public int ownerOf(int region) {
		synchronized (owners) {
			return owners[region];
		}
	}

	/**
	 * @return the regions that currently hold at least one of {@code chunks}, in ascending order
	 *
	 * <p>Ascending order is what makes the schedule deterministic: two runs with the same entity
	 * set fetch regions in the same order, so deferred effects are also applied in the same order.
	 */
	public int[] regionsFor(List<long[]> chunks) {
		boolean[] hit = new boolean[owners.length];
		List<Integer> result = new ArrayList<>();

		for (long[] chunk : chunks) {
			int region = regionOf((int) chunk[0], (int) chunk[1]);

			if (!hit[region]) {
				hit[region] = true;
				result.add(region);
			}
		}

		result.sort(Integer::compareTo);

		int[] out = new int[result.size()];

		for (int i = 0; i < out.length; i++) {
			out[i] = result.get(i);
		}

		return out;
	}

	private int index(int regionX, int regionZ) {
		int x = Math.max(0, Math.min(width - 1, regionX));
		int z = Math.max(0, regionZ);
		int index = z * width + x;

		if (index >= owners.length) {
			throw new IllegalStateException("region (" + regionX + "," + regionZ + ") outside grid of "
					+ owners.length + " regions; the area bounds were wrong");
		}

		return index;
	}

	/**
	 * Packs a chunk coordinate into one long, the form {@link #regionsFor} expects.
	 *
	 * @param chunkX chunk x
	 * @param chunkZ chunk z
	 * @return the packed coordinate
	 */
	public static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	/** @return the chunk x of a packed coordinate */
	public static int chunkX(long key) {
		return (int) (key >> 32);
	}

	/** @return the chunk z of a packed coordinate */
	public static int chunkZ(long key) {
		return (int) key;
	}
}
