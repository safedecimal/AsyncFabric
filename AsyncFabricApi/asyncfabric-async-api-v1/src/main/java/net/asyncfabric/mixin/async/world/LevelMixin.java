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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.asyncfabric.impl.async.world.ParallelWorldTick;
import net.asyncfabric.impl.async.world.RegionContext;

/**
 * Routes block writes made from a worker thread through the tick's write queue.
 *
 * <p>A worker ticking random block ticks will sometimes want to change a block. Writing directly
 * would mutate state owned by another region, which is the race that corrupts a save. Writing inside
 * the worker's own region is fine and stays direct; anything else is queued and applied at the
 * barrier on the game thread.
 *
 * <p>The return value is {@code true} because callers branch on it — grow the plant, play the sound,
 * place the item — and refusing would make them skip work that will in fact happen. That is the
 * deliberate trade: a correct answer is impossible before the write occurs, and a deferred write
 * that the caller believes succeeded is the only behaviour that keeps the world consistent.
 */
@Mixin(Level.class)
public abstract class LevelMixin implements net.asyncfabric.impl.async.world.BlockEntityTickerList {
	/** Vanilla's active ticker list. */
	@Shadow
	protected java.util.List<TickingBlockEntity> blockEntityTickers;

	/** Tickers waiting to join the active list next tick. */
	@Shadow
	protected java.util.List<TickingBlockEntity> pendingBlockEntityTickers;

	@Override
	public java.util.List<TickingBlockEntity> asyncfabricBlockEntityTickers() {
		return blockEntityTickers;
	}

	@Override
	public void asyncfabricMergePending() {
		if (!pendingBlockEntityTickers.isEmpty()) {
			blockEntityTickers.addAll(pendingBlockEntityTickers);
			pendingBlockEntityTickers.clear();
		}
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("HEAD"), cancellable = true)
	private void asyncfabricDeferCrossRegionWrite(BlockPos pos, BlockState state, int flags, int recursionLeft,
			CallbackInfoReturnable<Boolean> info) {
		RegionContext context = RegionContext.current();

		// the game thread owns everything and must never be routed through a queue
		if (context == null) return;

		Level level = (Level) (Object) this;

		if (context.owns(pos.getX() >> 4, pos.getZ() >> 4)) {
			// inside our own region: write directly, exactly like vanilla
			return;
		}

		long packed = pos.asLong();

		// the deferred write re-enters this method on the game thread, where no RegionContext is
		// installed, so it takes the direct path and is never queued twice
		info.setReturnValue(ParallelWorldTick.submitBlockWrite(level, packed,
				() -> level.setBlock(pos, state, flags, recursionLeft)));
	}

	/**
	 * Gives a worker its own random source while it ticks a region.
	 *
	 * <p>{@code Level.random} is a single {@code LegacyRandomSource}, which is not thread-safe: two
	 * workers drawing from it at once corrupt its seed and can produce the same value twice. Random
	 * ticks draw from it constantly, so without this the parallel path would be broken by
	 * construction.
	 */
	@Inject(method = "getRandom()Lnet/minecraft/util/RandomSource;", at = @At("HEAD"), cancellable = true)
	private void asyncfabricThreadLocalRandom(CallbackInfoReturnable<RandomSource> info) {
		RegionContext context = RegionContext.current();

		// the game thread owns the shared source and must not be redirected
		if (context == null) return;

		info.setReturnValue(ParallelWorldTick.workerRandom((Level) (Object) this));
	}

	/**
	 * Runs this level's block entity ticks in parallel where that is safe.
	 *
	 * <p>Vanilla's own loop is cancelled only when the replacement ticked every ticker. When parallel
	 * ticking is off, or there is nothing worth parallelising, this returns without cancelling and
	 * vanilla's loop runs unchanged.
	 */
	@Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
	private void asyncfabricParallelBlockEntities(CallbackInfo info) {
		// tickBlockEntities only runs server-side; this keeps a client call from reaching a
		// server-only path
		Object self = this;

		if (!(self instanceof net.minecraft.server.level.ServerLevel)) return;

		net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) self;

		if (!ParallelWorldTick.isEnabled()) return;

		java.util.List<TickingBlockEntity> tickers = ParallelWorldTick.collectBlockEntityTickers(level);

		if (tickers == null || tickers.size() < 2) return;

		ParallelWorldTick.tickBlockEntities(level, tickers, TickingBlockEntity::tick);

		// the replacement ticked every ticker and did vanilla's removal bookkeeping; letting the
		// original loop run as well would tick them twice
		info.cancel();
	}
}
