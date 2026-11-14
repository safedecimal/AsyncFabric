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

package net.asyncfabric.api.client.async.v1;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

import net.asyncfabric.api.async.v1.AsyncEvent;
import net.asyncfabric.api.async.v1.AsyncEventFactory;
import net.asyncfabric.impl.async.AsyncDispatch;

/**
 * Async counterparts of {@link net.asyncfabric.api.client.event.lifecycle.v1.ClientLifecycleEvents}.
 *
 * <p>Client lifecycle events fire off the game thread and the client thread waits for them,
 * with a timeout. Touching client state from a listener requires
 * {@link net.asyncfabric.api.async.v1.GameThread#runOnClient}.
 */
public final class AsyncClientLifecycleEvents {
	private AsyncClientLifecycleEvents() {
	}

	/**
	 * Called after the client finished starting, off the game thread.
	 *
	 * <p>The client thread waits for the returned future before the first frame.
	 */
	public static final AsyncEvent<ClientStarted> CLIENT_STARTED = AsyncEventFactory.createOrdered(
			ClientStarted.class, listeners -> client -> AsyncDispatch.chainAll(listeners, listener -> listener.onClientStarted(client)));

	/**
	 * Called when the client begins shutting down, off the game thread.
	 *
	 * <p>Nothing waits for this future; the client is closing.
	 */
	public static final AsyncEvent<ClientStopping> CLIENT_STOPPING = AsyncEventFactory.createOrdered(
			ClientStopping.class, listeners -> client -> AsyncDispatch.chainAll(listeners, listener -> listener.onClientStopping(client)));

	/**
	 * Async counterparts of the client tick events. The client thread never waits for these:
	 * blocking a frame would show as a stutter.
	 */
	public static final AsyncEvent<StartTick> START_CLIENT_TICK = AsyncEventFactory.createParallel(
			StartTick.class, listeners -> client -> AsyncDispatch.parallel("client_tick", listeners, listener -> listener.onStartTick(client)));

	public static final AsyncEvent<EndTick> END_CLIENT_TICK = AsyncEventFactory.createParallel(
			EndTick.class, listeners -> client -> AsyncDispatch.parallel("client_tick", listeners, listener -> listener.onEndTick(client)));

	@FunctionalInterface
	public interface ClientStarted {
		CompletableFuture<Void> onClientStarted(Minecraft client);
	}

	@FunctionalInterface
	public interface ClientStopping {
		CompletableFuture<Void> onClientStopping(Minecraft client);
	}

	@FunctionalInterface
	public interface StartTick {
		CompletableFuture<Void> onStartTick(Minecraft client);
	}

	@FunctionalInterface
	public interface EndTick {
		CompletableFuture<Void> onEndTick(Minecraft client);
	}
}
