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
 * Outcome of an attempted parallel entity tick.
 *
 * <p>The distinction matters more than it looks. Re-running the vanilla tick after a failed
 * parallel one is only safe when <em>no entity was advanced yet</em>; replaying after a partial
 * tick would tick those entities twice, which is exactly the duplication this whole design exists
 * to prevent. The caller therefore has to know which situation it is in, so the outcome is explicit
 * rather than a boolean.
 */
public enum TickOutcome {
	/** Every entity ticked in parallel; do not run anything else. */
	COMPLETED,

	/**
	 * Nothing was advanced — every region was claimed up front and at least one claim failed, or
	 * the run was abandoned before any entity started. Safe to run the vanilla sequential tick.
	 */
	SKIPPED,

	/**
	 * Some entities advanced and then the run failed. The world is consistent but the tick is
	 * incomplete; replaying it would double-tick the entities that already moved, so the caller
	 * must not replay. Reported loudly through {@link RaceGuard}.
	 */
	PARTIAL
}
