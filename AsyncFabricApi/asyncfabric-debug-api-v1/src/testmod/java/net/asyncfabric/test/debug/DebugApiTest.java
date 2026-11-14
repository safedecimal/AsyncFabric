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

package net.asyncfabric.test.debug;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.debug.DebugSubscription;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.decoration.Mannequin;

import net.asyncfabric.api.ModInitializer;
import net.asyncfabric.api.debug.v1.EntityDebugSubscriptionRegistry;
import net.asyncfabric.loader.api.AsyncFabricLoader;

public class DebugApiTest implements ModInitializer {
	public static DebugSubscription<SusDebugInfo> SUS_AVATAR;
	public static boolean DEBUG_SUS_AVATAR = true;

	@Override
	public void onInitialize() {
		if (AsyncFabricLoader.getInstance().isDevelopmentEnvironment()) {
			SUS_AVATAR = Registry.register(
					BuiltInRegistries.DEBUG_SUBSCRIPTION,
					Identifier.fromNamespaceAndPath(
							"asyncfabric-debug-api-v1-testmod",
							"sus_avatar"
					),
					new DebugSubscription<>(SusDebugInfo.STREAM_CODEC)
			);
			EntityDebugSubscriptionRegistry.<SusDebugInfo, Avatar>register(
					SUS_AVATAR,
					entity -> entity instanceof Avatar,
					avatar -> new SusDebugInfo(
							avatar.getPlainTextName(),
							avatar instanceof Mannequin
					),
					DEBUG_SUS_AVATAR
			);
		}
	}
}
