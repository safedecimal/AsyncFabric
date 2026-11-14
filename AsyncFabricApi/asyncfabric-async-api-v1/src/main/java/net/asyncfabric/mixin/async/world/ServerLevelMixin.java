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

package net.asyncfabric.mixin.async.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import net.asyncfabric.impl.async.world.ParallelWorldTick;
import net.asyncfabric.impl.async.world.TickOutcome;

/**
 * Runs the level's entity tick in parallel when that is safe.
 *
 * <p>The redirect sits on {@code EntityTickList.forEach} inside {@code ServerLevel.tick}. Vanilla
 * calls it with a consumer that ticks one entity; this replacement either drives that same consumer
 * from several threads over disjoint regions, or calls the original method so vanilla's own loop
 * runs untouched.
 *
 * <p>Handing {@code forEach} a different consumer is not enough on its own: the consumer closes
 * over the level and reads entity state. What makes the parallel path safe is everything in
 * {@code ParallelWorldTick} — region ownership, interaction grouping, deferred cross-region
 * effects, and the fallback. If any of that cannot guarantee safety, the original call happens and
 * the tick is exactly vanilla's.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	/** Guards against re-entering the parallel path from inside a listener. */
	@Unique
	private static final ThreadLocal<Boolean> ASYNCFABRIC_IN_TICK = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Redirect(
			method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V")
	)
	private void asyncfabricParallelEntityTick(EntityTickList tickList, Consumer<Entity> consumer) {
		ServerLevel level = (ServerLevel) (Object) this;

		// the entity set must be snapshotted before any thread starts: EntityTickList forbids
		// structural changes while it is being iterated, so a worker spawning an entity would
		// throw. Collecting first keeps iteration entirely on the game thread.
		List<Entity> entities = new ArrayList<>(Math.min(4096, 256));
		tickList.forEach(entities::add);

		if (!ParallelWorldTick.shouldParallelise(entities)) {
			// vanilla path, but over our snapshot; ordering and per-entity behaviour are identical
			for (Entity entity : entities) {
				consumer.accept(entity);
			}

			return;
		}

		if (Boolean.TRUE.equals(ASYNCFABRIC_IN_TICK.get())) {
			// a listener emitted another tick on this thread; never nest the parallel path
			for (Entity entity : entities) {
				consumer.accept(entity);
			}

			return;
		}

		ASYNCFABRIC_IN_TICK.set(Boolean.TRUE);

		try {
			TickOutcome outcome = ParallelWorldTick.tickEntities(level, entities, consumer, throwable ->
					org.slf4j.LoggerFactory.getLogger("AsyncFabric/World")
							.error("Entity tick failed for {}", level, throwable));

			if (outcome == TickOutcome.SKIPPED) {
				// nothing advanced, so vanilla's tick can run without double-processing anything
				for (Entity entity : entities) {
					consumer.accept(entity);
				}
			}

			// COMPLETED: nothing more to do.
			// PARTIAL: some entities already advanced; replaying would tick them twice, which is
			// the duplication the whole design exists to prevent. RaceGuard has already logged it
			// and will disable parallel ticking if it keeps happening.
		} finally {
			ASYNCFABRIC_IN_TICK.set(Boolean.FALSE);
		}
	}
}
