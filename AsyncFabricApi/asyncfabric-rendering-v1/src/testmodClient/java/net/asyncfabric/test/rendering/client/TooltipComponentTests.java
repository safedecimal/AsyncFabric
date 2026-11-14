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

package net.asyncfabric.test.rendering.client;

import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import net.asyncfabric.api.ClientModInitializer;
import net.asyncfabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.asyncfabric.test.rendering.TooltipComponentTestInit;

public class TooltipComponentTests implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTooltipComponentCallback.EVENT.register(data -> {
			if (data instanceof TooltipComponentTestInit.Data d) {
				return ClientTooltipComponent.create(Component.literal(d.string()).setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)).getVisualOrderText());
			}

			return null;
		});

		// Test that ClientTooltipComponent.create works with custom TooltipComponent
		Objects.requireNonNull(ClientTooltipComponent.create(new TooltipComponentTestInit.Data("Hello world!")));
	}
}
