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

package net.asyncfabric.api.async.v1;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;

import net.asyncfabric.impl.async.GameThreadImpl;

/**
 * Hands work back to the game thread.
 *
 * <p>Async listeners run on worker threads. Almost nothing in Minecraft may be touched
 * from there, so anything that mutates world, registry, player or network state has to
 * be scheduled back onto the server or client thread.
 *
 * <p>These methods never block the calling thread: they return a future that completes
 * once the task ran on the game thread.
 */
public final class GameThread {
	private GameThread() {
	}

	/**
	 * Runs {@code task} on the thread of the given server.
	 *
	 * @param server the server whose thread should run the task
	 * @param task the work to run
	 * @return a future completing after the task ran on the server thread
	 */
	public static CompletableFuture<Void> runOnServer(MinecraftServer server, Runnable task) {
		return GameThreadImpl.runOnServer(server, task);
	}

	/**
	 * Runs {@code task} on the client thread.
	 *
	 * <p>Only callable on a physical client; on a dedicated server this completes
	 * exceptionally.
	 *
	 * @param task the work to run
	 * @return a future completing after the task ran on the client thread
	 */
	public static CompletableFuture<Void> runOnClient(Runnable task) {
		return GameThreadImpl.runOnClient(task);
	}

	/**
	 * @param server the server to check
	 * @return {@code true} if the calling thread is the server's thread
	 */
	public static boolean isServerThread(MinecraftServer server) {
		return GameThreadImpl.isServerThread(server);
	}

	/**
	 * @return {@code true} if the calling thread is the client thread
	 */
	public static boolean isClientThread() {
		return GameThreadImpl.isClientThread();
	}

	/**
	 * @return the client instance, or {@code null} on a dedicated server
	 */
	public static Object client() {
		return GameThreadImpl.client();
	}
}
