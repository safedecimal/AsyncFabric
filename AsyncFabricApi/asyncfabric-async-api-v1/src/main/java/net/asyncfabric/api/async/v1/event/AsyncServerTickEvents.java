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

package net.asyncfabric.api.async.v1.event;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;

import net.asyncfabric.api.async.v1.AsyncEvent;
import net.asyncfabric.api.async.v1.AsyncEventFactory;
import net.asyncfabric.impl.async.AsyncDispatch;

/**
 * Async counterparts of {@link net.asyncfabric.api.event.lifecycle.v1.ServerTickEvents}.
 *
 * <p>These fire off the game thread and the server thread does <em>not</em> wait for them:
 * blocking every tick would cap the server at the slowest listener. All listeners of one tick
 * run concurrently.
 *
 * <p>Because ticks do not wait, a slow listener can still be running when the next tick fires.
 * {@link TickGate} prevents that by skipping (and counting) a tick while the previous one is
 * unfinished, so async work can never pile up without bound.
 */
public final class AsyncServerTickEvents {
	private AsyncServerTickEvents() {
	}

	/**
	 * Called at the start of every server tick, off the game thread.
	 *
	 * <p>The server thread does not wait for the result. A tick is skipped while the previous
	 * tick's listeners are still running — see {@link #droppedTicks()}.
	 */
	public static final AsyncEvent<StartTick> START_SERVER_TICK = AsyncEventFactory.createParallel(
			StartTick.class, listeners -> server -> AsyncDispatch.gatedParallel("start_server_tick", listeners, listener -> listener.onStartTick(server)));

	/**
	 * Called at the end of every server tick, off the game thread.
	 *
	 * <p>The server thread does not wait for the result. A tick is skipped while the previous
	 * tick's listeners are still running — see {@link #droppedTicks()}.
	 */
	public static final AsyncEvent<EndTick> END_SERVER_TICK = AsyncEventFactory.createParallel(
			EndTick.class, listeners -> server -> AsyncDispatch.gatedParallel("end_server_tick", listeners, listener -> listener.onEndTick(server)));

	/**
	 * @param gate {@code "start_server_tick"} or {@code "end_server_tick"}
	 * @return how many ticks were skipped because the previous one had not finished yet
	 */
	public static long droppedTicks(String gate) {
		return AsyncDispatch.dropped(gate);
	}

	@FunctionalInterface
	public interface StartTick {
		CompletableFuture<Void> onStartTick(MinecraftServer server);
	}

	@FunctionalInterface
	public interface EndTick {
		CompletableFuture<Void> onEndTick(MinecraftServer server);
	}
}
