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

package net.asyncfabric.impl.async;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;

/**
 * Environment-neutral half of {@link net.asyncfabric.api.async.v1.GameThread}.
 *
 * <p>The physical client installs a {@link ClientBridge} at startup; on a dedicated
 * server none is present, and client-side calls fail cleanly instead of dragging
 * client classes onto the server class path.
 */
public final class GameThreadImpl {
	/** Implemented in the client source set. */
	public interface ClientBridge {
		CompletableFuture<Void> runOnClient(Runnable task);

		boolean isClientThread();

		Object client();
	}

	private static volatile ClientBridge clientBridge;

	private GameThreadImpl() {
	}

	/** Called by the client source set once the client instance exists. */
	public static void installClientBridge(ClientBridge bridge) {
		clientBridge = bridge;
	}

	public static CompletableFuture<Void> runOnServer(MinecraftServer server, Runnable task) {
		if (server == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("server is null"));
		}

		return CompletableFuture.runAsync(task, server);
	}

	public static CompletableFuture<Void> runOnClient(Runnable task) {
		ClientBridge bridge = clientBridge;

		if (bridge == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"runOnClient is only available on a physical client"));
		}

		return bridge.runOnClient(task);
	}

	public static boolean isServerThread(MinecraftServer server) {
		return server != null && server.isSameThread();
	}

	public static boolean isClientThread() {
		ClientBridge bridge = clientBridge;

		return bridge != null && bridge.isClientThread();
	}

	public static Object client() {
		ClientBridge bridge = clientBridge;

		return bridge != null ? bridge.client() : null;
	}
}
