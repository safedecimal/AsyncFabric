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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which thread may touch which part of the world right now.
 *
 * <p>Minecraft's world is a single big mutable graph that vanilla touches from one thread only.
 * Running entity ticks in parallel is only safe if two threads never reach the same state at the
 * same time, so ownership is made explicit: a region is checked out by exactly one thread, and
 * every world access goes through {@link #acquire}/{@link #release} or is rejected.
 *
 * <p>This is the primitive the whole parallel world rests on. It is deliberately small and free of
 * Minecraft types so it can be tested as a pure state machine.
 */
public final class RegionOwnership {
	/** No thread owns the region. */
	static final int FREE = -1;

	private final AtomicInteger owner = new AtomicInteger(FREE);

	/**
	 * Tries to take ownership for {@code threadId}.
	 *
	 * @param threadId the owner's thread id
	 * @return {@code true} if the caller now owns the region; {@code false} if somebody else does
	 */
	public boolean acquire(int threadId) {
		while (true) {
			int current = owner.get();

			if (current == threadId) return true; // already ours, re-entrant
			if (current != FREE) return false;

			if (owner.compareAndSet(FREE, threadId)) return true;
		}
	}

	/**
	 * Releases the region.
	 *
	 * @param threadId the releasing thread
	 * @throws IllegalStateException if {@code threadId} does not own the region — that would mean
	 *         ownership was corrupted, and silently ignoring it would hide the real bug
	 */
	public void release(int threadId) {
		if (!owner.compareAndSet(threadId, FREE)) {
			throw new IllegalStateException("region released by thread " + threadId
					+ " but owned by " + owner.get());
		}
	}

	/**
	 * @param threadId the thread to check
	 * @return {@code true} if {@code threadId} owns this region
	 */
	public boolean isOwnedBy(int threadId) {
		return owner.get() == threadId;
	}

	/** @return {@code true} if nobody owns the region */
	public boolean isFree() {
		return owner.get() == FREE;
	}

	/** @return the owning thread id, or {@link #FREE} */
	public int ownerThread() {
		return owner.get();
	}
}
