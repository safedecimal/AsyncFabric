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

package net.asyncfabric.test.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;

import net.asyncfabric.api.ModInitializer;
import net.asyncfabric.api.async.v1.AsyncScheduler;
import net.asyncfabric.api.async.v1.GameThread;
import net.asyncfabric.api.async.v1.event.AsyncServerLifecycleEvents;
import net.asyncfabric.api.async.v1.event.AsyncServerTickEvents;

/**
 * Registers a few async listeners and logs which thread they ran on.
 *
 * <p>This is the in-game smoke test: the log must show {@code AsyncFabric-Worker-N} for the
 * listener bodies and the server thread name for anything handed back through
 * {@link GameThread}.
 */
public final class AsyncTestMod implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("AsyncFabric/AsyncTestMod");
	private static final AtomicInteger TICKS = new AtomicInteger();
	private static final AtomicInteger PROBE_EXPECTED = new AtomicInteger();
	/** Tick at which the probe spawns its entities, after the world is fully up. */
	private static final int PROBE_SPAWN_TICK = 40;

	@Override
	public void onInitialize() {
		AsyncServerLifecycleEvents.SERVER_STARTING.register(server -> {
			LOGGER.info("async SERVER_STARTING on thread '{}' (pool size {})",
					Thread.currentThread().getName(), AsyncScheduler.threadCount());

			return CompletableFuture.completedFuture(null);
		});

		AsyncServerLifecycleEvents.SERVER_STARTED.register(AsyncTestMod::onServerStarted);

		AsyncServerTickEvents.START_SERVER_TICK.register(server -> {
			int tick = TICKS.incrementAndGet();

			if (tick % 200 == 0) {
				LOGGER.info("async tick {} on thread '{}', dropped={}",
						tick, Thread.currentThread().getName(), AsyncServerTickEvents.droppedTicks("start_server_tick"));
			}

			// the overworld does not exist yet while SERVER_STARTED runs, so the probe spawns
			// from a tick instead
			if (tick == PROBE_SPAWN_TICK && Boolean.getBoolean("asyncfabric.world.probe") && PROBE_EXPECTED.get() == 0) {
				// the async tick listener runs on a worker, and world state must only be mutated on
				// the server thread, so the spawn is handed over instead of done here
				server.execute(() -> spawnParallelTickProbe(server));
			}

			verifyProbeCount(server, tick);

			return CompletableFuture.completedFuture(null);
		});

		LOGGER.info("async test mod initialised");
	}

	private static CompletableFuture<Void> onServerStarted(MinecraftServer server) {
		LOGGER.info("async SERVER_STARTED on thread '{}'", Thread.currentThread().getName());

		// prove the marshal-back path works: this must log on the server thread
		return GameThread.runOnServer(server, () -> {
			LOGGER.info("GameThread.runOnServer ran on thread '{}' (isServerThread={})",
					Thread.currentThread().getName(), GameThread.isServerThread(server));
		});
	}

	/**
	 * Spawns a known number of entities and checks, after enough ticks, that the count neither
	 * grows nor shrinks.
	 *
	 * <p>This is the anti-dupe assertion: if parallel ticking advanced an entity twice, vanillas
	 * own age/despawn logic or a mod's spawn logic would show up as a changed count. The expected
	 * count is fixed and the actual count is logged, so a broken tick is visible in the log rather
	 * than silently corrupting the save.
	 */
	private static void spawnParallelTickProbe(MinecraftServer server) {
		if (!Boolean.getBoolean("asyncfabric.world.probe")) {
			return; // opt-in: only the dedicated probe run wants entities in the world
		}

		net.minecraft.server.level.ServerLevel level = server.overworld();

		if (level == null) return;

		// near the spawn point, because chunks only tick when they are loaded and no player is
		// online in this run; further away the entities would simply not be part of the tick set
		net.minecraft.core.BlockPos spawn = level.getRespawnData().globalPos().pos();
		int baseY = spawn.getY();

		int[][] offsets = { { 0, 0 }, { 8, 0 }, { 16, 0 }, { 0, 8 }, { 8, 8 }, { 16, 8 } };
		int spawned = 0;

		for (int[] offset : offsets) {
			net.minecraft.core.BlockPos at = spawn.offset(offset[0], 0, offset[1]);
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());

			net.minecraft.world.entity.Entity cow = net.minecraft.world.entity.EntityTypes.COW.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);

			if (cow == null) continue;

			cow.setPos(at.getX() + 0.5, Math.max(y, baseY), at.getZ() + 0.5);

			if (level.addFreshEntity(cow)) {
				spawned++;
			}
		}

		PROBE_EXPECTED.set(spawned);
		LOGGER.info("world probe: spawned {} entities near {} for the parallel tick test", spawned, spawn);
	}

	/**
	 * Logs the entity count every 100 ticks and shouts if it drifted from the expected value.
	 *
	 * <p>A parallel tick that advanced an entity twice, or dropped one, changes the count. That is
	 * the observable symptom of the races this design guards against, so it is checked explicitly
	 * instead of trusting that nothing went wrong.
	 */
	private static void verifyProbeCount(MinecraftServer server, int tick) {
		int expected = PROBE_EXPECTED.get();

		if (expected <= 0 || tick % 100 != 0) return;

		net.minecraft.server.level.ServerLevel level = server.overworld();

		if (level == null) return;

		int actual = 0;

		for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
			if (entity.getType() == net.minecraft.world.entity.EntityTypes.COW) actual++;
		}

		if (actual == expected) {
			LOGGER.info("world probe at tick {}: {} entities, count stable", tick, actual);
		} else {
			LOGGER.error("WORLD PROBE MISMATCH at tick {}: expected {} entities, found {}. "
					+ "A parallel tick likely advanced or dropped an entity.", tick, expected, actual);
		}
	}
}
