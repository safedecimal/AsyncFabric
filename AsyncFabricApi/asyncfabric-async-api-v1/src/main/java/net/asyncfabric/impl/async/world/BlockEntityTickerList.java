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

package net.asyncfabric.impl.async.world;

import java.util.List;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * Accessor contract for a level's block-entity ticker lists.
 *
 * <p>The mixin on {@code Level} implements this interface so plain Java code can reach the
 * protected {@code blockEntityTickers} field without reflection. Declaring it as a normal
 * interface (rather than casting to the mixin class) is what makes the call compile: the compiler
 * cannot see that a mixin will be applied.
 */
public interface BlockEntityTickerList {
	/** @return the level's active ticking block entities */
	List<TickingBlockEntity> asyncfabricBlockEntityTickers();

	/**
	 * Moves tickers queued during the previous tick into the active list, matching what vanilla does
	 * at the top of {@code tickBlockEntities}.
	 */
	void asyncfabricMergePending();
}
