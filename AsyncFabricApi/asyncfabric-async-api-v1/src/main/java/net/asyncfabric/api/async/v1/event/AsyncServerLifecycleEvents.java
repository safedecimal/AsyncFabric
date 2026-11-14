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
 * Async counterparts of {@link net.asyncfabric.api.event.lifecycle.v1.ServerLifecycleEvents}.
 *
 * <p>These fire <em>in addition to</em> the synchronous events, off the game thread. The game
 * thread waits for them before continuing, with a timeout — lifecycle moments gate progress,
 * and letting the server tick before mods finished their startup work would be worse than a
 * short delay.
 *
 * <p>Listeners must not touch the world: they run on a worker thread. Hand work back with
 * {@link net.asyncfabric.api.async.v1.GameThread#runOnServer}. Listeners run one at a time,
 * in registration order.
 */
public final class AsyncServerLifecycleEvents {
	private AsyncServerLifecycleEvents() {
	}

	/**
	 * Called before the server initialises, off the game thread.
	 *
	 * <p>The server thread waits for the returned future before calling {@code initServer()}.
	 */
	public static final AsyncEvent<ServerStarting> SERVER_STARTING = AsyncEventFactory.createOrdered(
			ServerStarting.class, listeners -> server -> AsyncDispatch.chainAll(listeners, listener -> listener.onServerStarting(server)));

	/**
	 * Called after the server initialised but before it starts ticking, off the game thread.
	 *
	 * <p>The server thread waits for the returned future before accepting connections.
	 */
	public static final AsyncEvent<ServerStarted> SERVER_STARTED = AsyncEventFactory.createOrdered(
			ServerStarted.class, listeners -> server -> AsyncDispatch.chainAll(listeners, listener -> listener.onServerStarted(server)));

	/**
	 * Called when the server starts shutting down, off the game thread.
	 *
	 * <p>The server thread waits for the returned future before closing levels.
	 */
	public static final AsyncEvent<ServerStopping> SERVER_STOPPING = AsyncEventFactory.createOrdered(
			ServerStopping.class, listeners -> server -> AsyncDispatch.chainAll(listeners, listener -> listener.onServerStopping(server)));

	/**
	 * Called after the server stopped and all levels are closed, off the game thread.
	 *
	 * <p>Nothing waits for this future; the process is already on its way out.
	 */
	public static final AsyncEvent<ServerStopped> SERVER_STOPPED = AsyncEventFactory.createOrdered(
			ServerStopped.class, listeners -> server -> AsyncDispatch.chainAll(listeners, listener -> listener.onServerStopped(server)));

	@FunctionalInterface
	public interface ServerStarting {
		CompletableFuture<Void> onServerStarting(MinecraftServer server);
	}

	@FunctionalInterface
	public interface ServerStarted {
		CompletableFuture<Void> onServerStarted(MinecraftServer server);
	}

	@FunctionalInterface
	public interface ServerStopping {
		CompletableFuture<Void> onServerStopping(MinecraftServer server);
	}

	@FunctionalInterface
	public interface ServerStopped {
		CompletableFuture<Void> onServerStopped(MinecraftServer server);
	}
}
