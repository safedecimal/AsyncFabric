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

package net.asyncfabric.mixin.async;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;

import net.asyncfabric.api.async.v1.event.AsyncServerLifecycleEvents;
import net.asyncfabric.api.async.v1.event.AsyncServerTickEvents;
import net.asyncfabric.impl.async.AsyncAwait;

/**
 * Fires the async lifecycle events next to the synchronous ones.
 *
 * <p>Both injections sit at the same points as
 * {@code net.asyncfabric.mixin.event.lifecycle.MinecraftServerMixin} but use
 * {@code shift = AFTER} to run after the synchronous event, so sync behaviour and
 * listener ordering are unchanged.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z", shift = At.Shift.BEFORE), method = "runServer")
	private void asyncServerStarting(CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		AsyncAwait.blockOn(AsyncServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server), "SERVER_STARTING", server);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()Lnet/minecraft/network/protocol/status/ServerStatus;", ordinal = 0), method = "runServer")
	private void asyncServerStarted(CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		AsyncAwait.blockOn(AsyncServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server), "SERVER_STARTED", server);
	}

	@Inject(at = @At("HEAD"), method = "stopServer")
	private void asyncServerStopping(CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		AsyncAwait.blockOn(AsyncServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server), "SERVER_STOPPING", server);
	}

	@Inject(at = @At("TAIL"), method = "stopServer")
	private void asyncServerStopped(CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		// do not block here: the process is already shutting down
		AsyncServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickChildren(Ljava/util/function/BooleanSupplier;)V"), method = "tickServer")
	private void asyncStartTick(BooleanSupplier shouldKeepTicking, CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		// fire and forget on purpose: blocking every tick would cap the server at
		// the slowest listener
		AsyncServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server);
	}

	@Inject(at = @At("TAIL"), method = "tickServer")
	private void asyncEndTick(BooleanSupplier shouldKeepTicking, CallbackInfo info) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		AsyncServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
	}
}
