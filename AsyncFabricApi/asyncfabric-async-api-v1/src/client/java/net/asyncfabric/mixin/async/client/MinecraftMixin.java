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

package net.asyncfabric.mixin.async.client;

import java.util.concurrent.CompletableFuture;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import net.asyncfabric.api.client.async.v1.AsyncClientLifecycleEvents;
import net.asyncfabric.impl.async.AsyncAwait;
import net.asyncfabric.impl.async.GameThreadImpl;

/**
 * Installs the client half of {@link net.asyncfabric.api.async.v1.GameThread} and fires the
 * async client lifecycle and tick events.
 *
 * <p>The client instance is captured from {@code Minecraft.run()} so later calls to
 * {@code GameThread.runOnClient} have a target.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;gameThread:Ljava/lang/Thread;", shift = At.Shift.AFTER, ordinal = 0, opcode = Opcodes.PUTFIELD), method = "run")
	private void asyncInstallClientBridge(CallbackInfo info) {
		Minecraft client = (Minecraft) (Object) this;

		GameThreadImpl.installClientBridge(new GameThreadImpl.ClientBridge() {
			@Override
			public CompletableFuture<Void> runOnClient(Runnable task) {
				return CompletableFuture.runAsync(task, client);
			}

			@Override
			public boolean isClientThread() {
				return client.isSameThread();
			}

			@Override
			public Object client() {
				return client;
			}
		});

		AsyncAwait.blockOn(AsyncClientLifecycleEvents.CLIENT_STARTED.invoker().onClientStarted(client), "CLIENT_STARTED", client);
	}

	@Inject(at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V", shift = At.Shift.AFTER), method = "exitWorldAndClose")
	private void asyncClientStopping(CallbackInfo info) {
		Minecraft client = (Minecraft) (Object) this;
		AsyncClientLifecycleEvents.CLIENT_STOPPING.invoker().onClientStopping(client);
	}

	@Inject(at = @At("HEAD"), method = "tick")
	private void asyncStartClientTick(CallbackInfo info) {
		Minecraft client = (Minecraft) (Object) this;
		AsyncClientLifecycleEvents.START_CLIENT_TICK.invoker().onStartTick(client);
	}

	@Inject(at = @At("RETURN"), method = "tick")
	private void asyncEndClientTick(CallbackInfo info) {
		Minecraft client = (Minecraft) (Object) this;
		AsyncClientLifecycleEvents.END_CLIENT_TICK.invoker().onEndTick(client);
	}
}
