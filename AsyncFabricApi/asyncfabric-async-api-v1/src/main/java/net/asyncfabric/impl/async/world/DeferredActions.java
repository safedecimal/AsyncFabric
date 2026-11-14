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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cross-region effects that must not be applied while the world is being ticked in parallel.
 *
 * <p>An entity ticking in region A may want to do something that belongs to region B: push a
 * neighbour block, apply damage, spawn a particle entity, schedule a task. Applying it immediately
 * from A's thread would mutate state owned by B, which is exactly the race that produces dupes.
 *
 * <p>Instead the effect is queued and applied at the barrier, after every region finished, on the
 * game thread, in a deterministic order. Two properties matter:
 *
 * <ul>
 *     <li><b>Ordering.</b> Effects are applied in the order they were queued, and the queue is
 *     drained in a fixed sequence. A run over the same input therefore produces the same world,
 *     which is what makes this debuggable at all.</li>
 *     <li><b>Exactly once.</b> Each queued effect carries a sequence number and a de-duplication
 *     key. If two regions both decide to, say, drop one item, the key makes it one drop, not
 *     two — the classic dupe.</li>
 * </ul>
 */
public final class DeferredActions {
	private final AtomicLong sequence = new AtomicLong();
	private final List<Entry> queue = new ArrayList<>();
	private final ConcurrentHashMap<String, Boolean> dedupeKeys = new ConcurrentHashMap<>();
	private volatile boolean accepting = true;

	/**
	 * Queues an effect to run at the barrier.
	 *
	 * @param action the effect, run on the game thread
	 */
	public void enqueue(Runnable action) {
		enqueue(null, action);
	}

	/**
	 * Queues an effect that must happen at most once per tick.
	 *
	 * @param dedupeKey identifies the effect; a second enqueue with the same key in the same tick
	 *                  is dropped, which is what stops two regions from applying the same change
	 * @param action the effect, run on the game thread
	 */
	public void enqueue(String dedupeKey, Runnable action) {
		if (!accepting) {
			throw new IllegalStateException("deferred actions are no longer accepted; "
					+ "the tick already reached its barrier");
		}

		if (dedupeKey != null && dedupeKeys.putIfAbsent(dedupeKey, Boolean.TRUE) != null) {
			// another region already queued this exact effect this tick
			return;
		}

		synchronized (queue) {
			queue.add(new Entry(sequence.getAndIncrement(), action));
		}
	}

	/**
	 * Runs every queued effect on the calling thread, in queue order, then clears the queue.
	 *
	 * <p>Called once per tick, on the game thread, after all regions finished. Stopping here is
	 * deliberate: the barrier must not accept new work, otherwise a region could enqueue for a
	 * tick that is already over.
	 *
	 * @param failureHandler receives failures; one failing effect must not prevent the rest, since
	 *                       skipping them would leave the world half-updated
	 */
	public void drain(Consumer<Throwable> failureHandler) {
		accepting = false;

		List<Entry> pending;

		synchronized (queue) {
			if (queue.isEmpty()) {
				accepting = true;
				dedupeKeys.clear();

				return;
			}

			pending = new ArrayList<>(queue);
			queue.clear();
		}

		pending.sort(Comparator.comparingLong(entry -> entry.sequence));

		for (Entry entry : pending) {
			try {
				entry.action.run();
			} catch (Throwable t) {
				failureHandler.accept(t);
			}
		}

		dedupeKeys.clear();
		accepting = true;
	}

	/** @return how many effects are waiting for the barrier */
	public int size() {
		synchronized (queue) {
			return queue.size();
		}
	}

	/** @return {@code true} if nothing is queued */
	public boolean isEmpty() {
		return size() == 0;
	}

	/** Test hook: drops everything without running it. */
	public void clear() {
		synchronized (queue) {
			queue.clear();
		}

		dedupeKeys.clear();
	}

	private static final class Entry {
		final long sequence;
		final Runnable action;

		Entry(long sequence, Runnable action) {
			this.sequence = sequence;
			this.action = action;
		}
	}
}
