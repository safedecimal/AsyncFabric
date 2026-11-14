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

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches parallel world ticks and degrades to a single thread when they are not safe.
 *
 * <p>No amount of static reasoning makes a modded world provably race-free: a mod can reach any
 * object from an entity tick. Rather than hoping, this class treats a detected problem as a signal
 * to stop parallelising — the tick finishes sequentially, the world stays consistent, and the
 * failure is logged with enough detail to act on.
 *
 * <p>Two failure modes are handled differently:
 *
 * <ul>
 *   <li><b>A single tick failed.</b> The tick is retried on the game thread. Vanilla re-runs a
 *   failed level tick this way anyway, so the world ends up as if parallelism never happened.</li>
 *   <li><b>Violations keep happening.</b> Parallel entity ticking is switched off for the rest of
 *   the session. Continuing to retry every tick would turn a correctness problem into a permanent
 *   performance problem.</li>
 * </ul>
 */
public final class RaceGuard {
	private static final Logger LOGGER = LoggerFactory.getLogger("AsyncFabric/RaceGuard");

	/** Consecutive failures after which parallel ticking is abandoned for the session. */
	private static final int FAILURE_LIMIT = 3;

	private final AtomicLong parallelTicks = new AtomicLong();
	private final AtomicLong sequentialFallbacks = new AtomicLong();
	private final AtomicLong lastFailureTick = new AtomicLong(-1);

	private volatile boolean parallelEnabled = true;
	private volatile int consecutiveFailures;
	private volatile String disableReason;

	/** @return {@code true} if world ticks may currently run in parallel */
	public boolean isParallelEnabled() {
		return parallelEnabled;
	}

	/**
	 * Records a tick that ran in parallel without problems.
	 *
	 * @param tick the server tick number
	 */
	public void recordParallelSuccess(long tick) {
		parallelTicks.incrementAndGet();
		consecutiveFailures = 0;
	}

	/**
	 * Records a tick that had to fall back, and disables parallel ticking if this keeps happening.
	 *
	 * @param tick the server tick number
	 * @param cause what went wrong
	 */
	public void recordFallback(long tick, Throwable cause) {
		sequentialFallbacks.incrementAndGet();
		lastFailureTick.set(tick);

		int failures = ++consecutiveFailures;

		LOGGER.error("Parallel world tick {} failed, falling back to a sequential tick for this level. "
				+ "This keeps the world consistent but loses the speedup for this tick.", tick, cause);

		if (failures >= FAILURE_LIMIT && parallelEnabled) {
			parallelEnabled = false;
			disableReason = cause != null ? cause.toString() : "unknown";
			LOGGER.error("Disabling parallel world ticks for this session after {} consecutive failures. "
					+ "Last cause: {}. Report this with the log and the mod list; entity ticking falls back "
					+ "to vanilla behaviour until the cause is fixed.", failures, disableReason);
		}
	}

	/**
	 * Records that a region was touched by a thread that did not own it.
	 *
	 * <p>This is the loudest signal available: it means the ownership model was violated, so the
	 * parallel tick that produced it cannot be trusted.
	 *
	 * @param region the region index
	 * @param owner the thread that owned it
	 * @param intruder the thread that touched it
	 */
	public void recordOwnershipViolation(int region, int owner, int intruder) {
		LOGGER.error("Region {} owned by thread {} was touched by thread {}. "
				+ "This is a race in a mod's entity tick; the tick will be retried sequentially.",
				region, owner, intruder);
		recordFallback(lastFailureTick.get() < 0 ? 0 : lastFailureTick.get(),
				new IllegalStateException("region " + region + " touched by non-owner thread " + intruder));
	}

	/** @return ticks that ran fully in parallel */
	public long parallelTicks() {
		return parallelTicks.get();
	}

	/** @return ticks that had to fall back */
	public long sequentialFallbacks() {
		return sequentialFallbacks.get();
	}

	/** @return why parallel ticking was disabled, or {@code null} */
	public String disableReason() {
		return disableReason;
	}

	/** Test hook: restores the initial state. */
	public void reset() {
		parallelTicks.set(0);
		sequentialFallbacks.set(0);
		lastFailureTick.set(-1);
		consecutiveFailures = 0;
		parallelEnabled = true;
		disableReason = null;
	}
}
