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

package net.asyncfabric.test.tag;

import net.minecraft.resources.Identifier;

import net.asyncfabric.api.ModInitializer;
import net.asyncfabric.api.resource.v1.ResourceLoader;
import net.asyncfabric.api.resource.v1.pack.PackActivationType;
import net.asyncfabric.loader.api.AsyncFabricLoader;
import net.asyncfabric.loader.api.ModContainer;

public class TagTest implements ModInitializer {
	public static final String MOD_ID = "asyncfabric-tag-api-v1-testmod";

	public static final Identifier REMOVE_AND_ADD_TEST_PACK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "remove_and_add_test");
	public static final Identifier NON_PRESENT_REMOVAL_PACK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "non_present_removal");

	@Override
	public void onInitialize() {
		final ModContainer container = AsyncFabricLoader.getInstance().getModContainer(MOD_ID).get();

		if (!ResourceLoader.registerBuiltinPack(REMOVE_AND_ADD_TEST_PACK_ID, container, PackActivationType.NORMAL)) {
			throw new IllegalStateException("Could not register '%s' built-in resource pack.".formatted(REMOVE_AND_ADD_TEST_PACK_ID));
		}

		if (!ResourceLoader.registerBuiltinPack(NON_PRESENT_REMOVAL_PACK_ID, container, PackActivationType.NORMAL)) {
			throw new IllegalStateException("Could not register '%s' built-in resource pack.".formatted(NON_PRESENT_REMOVAL_PACK_ID));
		}
	}
}
