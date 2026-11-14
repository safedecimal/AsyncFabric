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

package net.asyncfabric.mixin.async.world;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import net.asyncfabric.impl.async.world.ParallelWorldTick;

/**
 * Replaces vanilla's chunk walk with a parallel one when that is safe.
 *
 * <p>This redirects the {@code chunkMap.forEachBlockTickingChunk(consumer)} call inside
 * {@code ServerChunkCache.tickChunks}. That is the one place with the whole set of block-ticking
 * chunks in hand, which is what makes region partitioning possible: {@code forEachBlockTickingChunk}
 * itself only ever sees one chunk at a time.
 *
 * <p>The replacement either partitions the chunks and ticks each region on its own thread, or calls
 * the original method so vanilla's loop runs untouched. Correctness lives outside this mixin: region
 * ownership in {@code ParallelChunkTick}, cross-region block writes deferred through
 * {@code BlockWriteQueue}, and a per-worker random source because {@code Level.random} is not
 * thread-safe.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
	@Shadow
	public ChunkMap chunkMap;

	@Shadow
	private ServerLevel level;

	@Redirect(
			method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
	)
	private void asyncfabricParallelChunkTicks(ChunkMap map, Consumer<LevelChunk> ticker) {
		ParallelWorldTick.tickChunks(level, map, ticker);
	}
}
