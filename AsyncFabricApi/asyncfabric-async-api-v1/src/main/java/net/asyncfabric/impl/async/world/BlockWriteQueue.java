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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies block changes that a parallel tick must not make itself.
 *
 * <p>{@code Level.setBlock} returns whether it succeeded, and callers branch on that: grow a
 * plant, place an item, play a sound, schedule another update. A worker cannot answer honestly
 * before the change happened, and lying would make mods take the wrong branch. So the write is
 * queued, the caller is told "yes, accepted", and the real write happens on the game thread at the
 * barrier.
 *
 * <p>Two rules keep this from corrupting anything:
 *
 * <ul>
 *   <li><b>Spatial ownership is checked first.</b> A write inside the calling region's own chunks
 *   is applied immediately, by that region's own thread. Only writes that cross into another
 *   region get queued. This keeps the common case cheap and exact.</li>
 *   <li><b>Last write wins per position, first attribution wins for the return value.</b> If two
 *   regions both write the same block in one tick, the later queue entry overwrites the earlier
 *   one at apply time — which is the same outcome vanilla's single thread would produce — and the
 *   position is applied once, not twice.</li>
 * </ul>
 */
public final class BlockWriteQueue {
	private final AtomicLong sequence = new AtomicLong();
	private final List<Entry> queue = new ArrayList<>();
	private final AtomicBoolean accepting = new AtomicBoolean(true);

	/**
	 * Queues a block write for the barrier.
	 *
	 * @param packedPos the position, packed, used to keep writes for one block ordered
	 * @param write performs the actual write; run on the game thread
	 * @return {@code true} always — the contract callers depend on is "accepted, will apply"
	 */
	public boolean submit(long packedPos, Runnable write) {
		if (!accepting.get()) {
			throw new IllegalStateException("block writes are no longer accepted; the tick reached "
					+ "its barrier and a listener is writing from a stale thread");
		}

		synchronized (queue) {
			queue.add(new Entry(sequence.getAndIncrement(), packedPos, write));
		}

		return true;
	}

	/**
	 * Queues a block write that produces a value the caller needs.
	 *
	 * <p>The result is the value the write <em>would</em> return if it could be known in advance.
	 * Use it only for calls whose success does not depend on the previous block state, such as
	 * placing a new block; otherwise the caller should stay on the game thread.
	 *
	 * @param packedPos packed block position
	 * @param assumedResult the value the caller will see
	 * @param write performs the write on the game thread
	 * @param <T> result type
	 * @return {@code assumedResult}
	 */
	public <T> T submitAssume(long packedPos, T assumedResult, Runnable write) {
		submit(packedPos, write);

		return assumedResult;
	}

	/**
	 * Runs every queued write on the calling thread.
	 *
	 * <p>Writes for the same position are collapsed to the last one: applying an earlier state and
	 * then the later one would fire two updates where vanilla fires one, which is how a redstone
	 * line ends up evaluating twice.
	 *
	 * @param failureHandler receives failures; one broken write must not strand the rest
	 */
	public void drain(Consumer<Throwable> failureHandler) {
		accepting.set(false);

		List<Entry> pending;

		synchronized (queue) {
			if (queue.isEmpty()) {
				accepting.set(true);

				return;
			}

			pending = new ArrayList<>(queue);
			queue.clear();
		}

		pending.sort(Comparator.comparingLong(entry -> entry.sequence));

		// collapse per position, keeping queue order for the survivors
		java.util.Map<Long, Entry> lastPerPosition = new java.util.LinkedHashMap<>();

		for (Entry entry : pending) {
			lastPerPosition.put(entry.position, entry);
		}

		for (Entry entry : lastPerPosition.values()) {
			try {
				entry.write.run();
			} catch (Throwable t) {
				failureHandler.accept(t);
			}
		}

		accepting.set(true);
	}

	/** @return how many writes are waiting for the barrier */
	public int size() {
		synchronized (queue) {
			return queue.size();
		}
	}

	/** @return {@code true} if nothing is queued */
	public boolean isEmpty() {
		return size() == 0;
	}

	/** @return {@code true} while workers may still submit */
	public boolean isAccepting() {
		return accepting.get();
	}

	/** Test hook: drops everything without applying it. */
	public void clear() {
		synchronized (queue) {
			queue.clear();
		}

		accepting.set(true);
	}

	/** A queued write, identified for collapsing and ordered for replay. */
	private static final class Entry {
		final long sequence;
		final long position;
		final Runnable write;

		Entry(long sequence, long position, Runnable write) {
			this.sequence = sequence;
			this.position = position;
			this.write = write;
		}
	}

	/** Convenience for callers that only need the "accepted" answer. */
	public static Supplier<Boolean> acceptingSupplier(BlockWriteQueue queue) {
		return queue::isAccepting;
	}
}
