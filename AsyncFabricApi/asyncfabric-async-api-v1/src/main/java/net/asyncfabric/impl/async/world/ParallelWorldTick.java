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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Entry point the level mixin calls to decide whether it may tick entities in parallel.
 *
 * <p>Keeps the decision out of the mixin: the mixin only asks "should I hand this tick over?",
 * and everything else — safety checks, the guard, the fallback — lives here, where it can be
 * read and tested.
 */
public final class ParallelWorldTick {
	private static final Logger LOGGER = LoggerFactory.getLogger("AsyncFabric/World");
	/** System property to force the vanilla single-threaded path. */
	public static final String DISABLE_PROPERTY = "asyncfabric.world.parallel";

	private static final RaceGuard GUARD = new RaceGuard();
	private static final ParallelLevelTick TICKER = new ParallelLevelTick(GUARD);
	private static final ParallelChunkTick CHUNK_TICKER = new ParallelChunkTick(GUARD);
	private static volatile boolean disabledByProperty;

	static {
		disabledByProperty = !Boolean.parseBoolean(System.getProperty(DISABLE_PROPERTY, "true"));
	}

	private ParallelWorldTick() {
	}

	/** @return {@code true} if world ticking may be parallelised this run */
	public static boolean isEnabled() {
		return !disabledByProperty && GUARD.isParallelEnabled();
	}

	/**
	 * Fire-and-forget wrapper used by the mixin for the cheap safety checks.
	 *
	 * @param entities the entities this level will tick
	 * @return {@code true} if the caller should use the parallel path
	 */
	public static boolean shouldParallelise(List<Entity> entities) {
		if (!isEnabled()) return false;

		// a single entity cannot overlap with anything; the bookkeeping would cost more than it saves
		return entities.size() > 1;
	}

	/** Set with {@code -Dasyncfabric.world.debug=true} to log every parallel tick. */
	private static final boolean DEBUG = Boolean.getBoolean("asyncfabric.world.debug");

	/**
	 * Write queue of the chunk/block-entity tick currently running, if any.
	 *
	 * <p>Held here rather than passed around because the mixin on {@code Level.setBlock} has no
	 * other way to reach it: block writes happen deep inside {@code randomTick} and block entity
	 * tickers, far from the orchestrator that would otherwise own the queue.
	 */
	private static volatile BlockWriteQueue activeWrites;
	/** Guards the one-time warning about a missing accessor mixin. */
	private static final AtomicBoolean ACCESSOR_WARNING = new AtomicBoolean();

	/**
	 * Makes {@code queue} the target for block writes issued by workers during this tick.
	 *
	 * @param queue the queue, or {@code null} to stop accepting
	 */
	public static void setActiveWriteQueue(BlockWriteQueue queue) {
		activeWrites = queue;
	}

	/** @return the queue workers should submit cross-region writes to, or {@code null} */
	public static BlockWriteQueue activeWriteQueue() {
		return activeWrites;
	}

	/**
	 * Base random source captured on the game thread, per level, for the duration of one tick.
	 *
	 * <p>Workers must never call {@code level.getRandom()} to obtain a fork: on a worker that call
	 * is redirected back into this class, which would recurse forever. The base is therefore
	 * captured once, on the game thread, before any worker starts.
	 */
	private static final java.util.Map<Object, net.minecraft.util.RandomSource> RANDOM_BASE =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Per-thread random sources for workers, keyed by level.
	 *
	 * <p>{@code Level.random} is a single {@code LegacyRandomSource} and is not thread-safe; two
	 * workers drawing from it at once corrupt its seed. Each worker therefore forks its own copy.
	 */
	private static final java.util.Map<Object, ThreadLocal<net.minecraft.util.RandomSource>> WORKER_RANDOM =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Captures the level's random source so workers can fork from it. Called on the game thread
	 * before a parallel tick starts.
	 *
	 * @param level the level about to be ticked
	 */
	public static void beginRandomScope(net.minecraft.world.level.Level level) {
		RANDOM_BASE.put(level, level.getRandom());
	}

	/**
	 * Releases the captured source and every worker's fork for this level. Called on the game
	 * thread after the parallel tick finished, so nothing leaks between ticks.
	 *
	 * @param level the level that was ticked
	 */
	public static void endRandomScope(net.minecraft.world.level.Level level) {
		RANDOM_BASE.remove(level);
		java.util.Map<Object, ThreadLocal<net.minecraft.util.RandomSource>> ignored = WORKER_RANDOM;
		ThreadLocal<net.minecraft.util.RandomSource> perThread = ignored.remove(level);

		if (perThread != null) perThread.remove();
	}

	/**
	 * @param level the level the calling worker is ticking
	 * @return a random source private to the calling thread, forked from the tick's base
	 */
	public static net.minecraft.util.RandomSource workerRandom(net.minecraft.world.level.Level level) {
		ThreadLocal<net.minecraft.util.RandomSource> perThread =
				WORKER_RANDOM.computeIfAbsent(level, ignored -> new ThreadLocal<>());

		net.minecraft.util.RandomSource source = perThread.get();

		if (source == null) {
			net.minecraft.util.RandomSource base = RANDOM_BASE.get(level);

			if (base == null) {
				// scope was not opened: this is a worker outside a parallel tick, which should not
				// happen. Fall back to the shared source rather than failing the tick.
				base = net.minecraft.util.RandomSource.create();
			}

			source = base.fork();
			perThread.set(source);
		}

		return source;
	}

	/**
	 * Queues a block write that a worker must not perform itself.
	 *
	 * @param level the level being written
	 * @param packedPos the packed block position
	 * @param write performs the write on the game thread
	 * @return the value the caller should see, i.e. {@code true}
	 */
	public static boolean submitBlockWrite(Object level, long packedPos, Runnable write) {
		BlockWriteQueue queue = activeWrites;

		if (queue == null) {
			// no parallel tick in flight: this should not happen, so run it rather than drop it
			LOGGER.warn("Block write from a worker outside a parallel tick; applying it directly");

			write.run();

			return true;
		}

		return queue.submit(packedPos, write);
	}

	/**
	 * Ticks the entities of {@code level} in parallel over disjoint regions.
	 *
	 * @param level the level being ticked
	 * @param entities the entities vanilla would tick
	 * @param failures receives per-entity failures
	 * @return how far the tick got; only {@link TickOutcome#SKIPPED} may be replayed
	 */
	public static TickOutcome tickEntities(ServerLevel level, List<Entity> entities, java.util.function.Consumer<Entity> vanillaTick,
			java.util.function.Consumer<Throwable> failures) {
		DeferredActions deferred = new DeferredActions();
		AtomicBoolean sawRemoval = new AtomicBoolean();

		try {
			TickOutcome outcome = TICKER.tickEntities(level, entities, vanillaTick, deferred, throwable -> {
				// a removal during a parallel tick means the entity set changed under the
				// iteration; entity lists are snapshots here, so it is safe, but a spawn would
				// have thrown already
				sawRemoval.set(true);
				failures.accept(throwable);
			});

			if (DEBUG) {
				LOGGER.info("Parallel world tick {} for {}: {} entities, {} deferred effects -> {}",
						level.getGameTime(), level.dimension(), entities.size(), deferred.size(), outcome);
			}

			return outcome;
		} catch (Throwable t) {
			// anything that escapes means the tick is not trustworthy; report and fall back
			LOGGER.error("Parallel entity tick for {} threw; falling back to a sequential tick", level, t);
			GUARD.recordFallback(level.getGameTime(), t);

			// nothing was claimed or ticked before the throw, so vanilla may run
			return TickOutcome.SKIPPED;
		}
	}

	/** @return the guard, for diagnostics and tests */
	public static RaceGuard guard() {
		return GUARD;
	}

	/**
	 * Ticks the block-ticking chunks of a level in parallel when that is safe, or runs vanilla's own
	 * loop otherwise.
	 *
	 * <p>Called from the redirect on {@code ServerChunkCache.tickChunks}. Collecting the chunks
	 * first is required: partitioning by region needs the whole set, and
	 * {@code forEachBlockTickingChunk} only exposes one chunk at a time.
	 *
	 * @param level the level being ticked
	 * @param map the chunk map providing the block-ticking chunks
	 * @param ticker vanilla's per-chunk consumer
	 */
	public static void tickChunks(ServerLevel level, net.minecraft.server.level.ChunkMap map,
			java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> ticker) {
		if (!isEnabled()) {
			map.forEachBlockTickingChunk(ticker);

			return;
		}

		List<net.minecraft.world.level.chunk.LevelChunk> chunks = new ArrayList<>();
		map.forEachBlockTickingChunk(chunks::add);

		if (chunks.size() < 2) {
			// nothing to overlap; vanilla's loop is cheaper than the bookkeeping
			for (net.minecraft.world.level.chunk.LevelChunk chunk : chunks) {
				ticker.accept(chunk);
			}

			return;
		}

		int[] chunkXs = new int[chunks.size()];
		int[] chunkZs = new int[chunks.size()];

		for (int i = 0; i < chunks.size(); i++) {
			long packed = chunks.get(i).getPos().pack();
			chunkXs[i] = net.minecraft.world.level.ChunkPos.getX(packed);
			chunkZs[i] = net.minecraft.world.level.ChunkPos.getZ(packed);
		}

		int size = RegionGrid.defaultRegionSize();
		TickPlan plan = TickPlan.build(chunkXs, chunkZs, List.of(), size);
		RegionGrid grid = gridFor(chunkXs, chunkZs, size);

		BlockWriteQueue writes = new BlockWriteQueue();
		beginRandomScope(level);
		setActiveWriteQueue(writes);

		try {
			TickOutcome outcome = CHUNK_TICKER.tickChunks(plan, grid, chunkXs, chunkZs,
					index -> ticker.accept(chunks.get(index)), writes,
					throwable -> LOGGER.error("Chunk tick failed in {}", level, throwable));

			if (outcome != TickOutcome.COMPLETED) {
				// nothing ran, or the run was abandoned before the barrier: replay vanilla's loop so
				// every chunk is ticked exactly once
				for (net.minecraft.world.level.chunk.LevelChunk chunk : chunks) {
					ticker.accept(chunk);
				}
			}

			if (DEBUG) {
				LOGGER.info("Parallel chunk tick {} for {}: {} chunks, {} deferred writes -> {}",
						level.getGameTime(), level.dimension(), chunks.size(), writes.size(), outcome);
			}
		} catch (Throwable t) {
			LOGGER.error("Parallel chunk tick for {} threw; falling back to vanilla", level, t);
			GUARD.recordFallback(level.getGameTime(), t);

			for (net.minecraft.world.level.chunk.LevelChunk chunk : chunks) {
				ticker.accept(chunk);
			}
		} finally {
			setActiveWriteQueue(null);
			endRandomScope(level);
		}
	}

	/**
	 * Collects a level's ticking block entities, doing vanilla's pending-to-active merge first.
	 *
	 * <p>Vanilla moves entries from {@code pendingBlockEntityTickers} into {@code
	 * blockEntityTickers} at the start of every tick; the replacement has to do the same or a block
	 * entity added this tick would wait a tick.
	 *
	 * @param level the level being ticked
	 * @return a snapshot of the tickers, or {@code null} if the list is not reachable
	 */
	public static List<net.minecraft.world.level.block.entity.TickingBlockEntity> collectBlockEntityTickers(
			ServerLevel level) {
		if (!(level instanceof BlockEntityTickerList)) {
			// the accessor mixin was not applied, which means the module is broken. Say so once
			// instead of silently losing the speedup with no trace in the log.
			if (ACCESSOR_WARNING.compareAndSet(false, true)) {
				LOGGER.error("Block entity accessor mixin is not applied ({} is not a {}); "
						+ "block entities will tick sequentially. This is a bug in the mixin config.",
						level.getClass().getName(), BlockEntityTickerList.class.getSimpleName());
			}

			return null;
		}

		BlockEntityTickerList tickers = (BlockEntityTickerList) level;

		// vanilla merges the pending list into the active one at the start of the tick; the
		// replacement does the same so a block entity placed this tick is not skipped
		tickers.asyncfabricMergePending();

		return new ArrayList<>(tickers.asyncfabricBlockEntityTickers());
	}

	/**
	 * Ticks block entities in parallel where {@link BlockEntitySafety} allows it.
	 *
	 * <p>The unsafe ones run on the game thread first, in vanilla order; only then do the safe ones
	 * go out to workers. Splitting this way means a hopper always observes the world as vanilla
	 * would, while a furnace next to it does not make it wait.
	 *
	 * @param level the level being ticked
	 * @param tickers every ticking block entity, in vanilla order
	 * @param tick runs one of them
	 */
	public static void tickBlockEntities(ServerLevel level,
			List<net.minecraft.world.level.block.entity.TickingBlockEntity> tickers,
			java.util.function.Consumer<net.minecraft.world.level.block.entity.TickingBlockEntity> tick) {
		if (!isEnabled() || tickers.size() < 2) return;

		List<String> types = new ArrayList<>(tickers.size());

		for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : tickers) {
			types.add(ticker.getType());
		}

		BlockEntitySafety.Partition partition = BlockEntitySafety.partition(types);
		java.util.function.Consumer<Throwable> failures =
				throwable -> LOGGER.error("Block entity tick failed in {}", level, throwable);

		if (!partition.worthParallelising()) {
			for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : tickers) {
				if (!ticker.isRemoved()) tick.accept(ticker);
			}

			return;
		}

		// unsafe ones first, on the game thread, before any worker looks at the world
		for (int index : partition.sequential()) {
			net.minecraft.world.level.block.entity.TickingBlockEntity ticker = tickers.get(index);

			if (!ticker.isRemoved()) tick.accept(ticker);
		}

		int[] safe = partition.parallel();
		int[] chunkXs = new int[safe.length];
		int[] chunkZs = new int[safe.length];

		for (int i = 0; i < safe.length; i++) {
			net.minecraft.core.BlockPos pos = tickers.get(safe[i]).getPos();
			chunkXs[i] = pos.getX() >> 4;
			chunkZs[i] = pos.getZ() >> 4;
		}

		int size = RegionGrid.defaultRegionSize();
		TickPlan plan = TickPlan.build(chunkXs, chunkZs, List.of(), size);
		RegionGrid grid = gridFor(chunkXs, chunkZs, size);
		BlockWriteQueue writes = new BlockWriteQueue();
		BlockWriteQueue previous = activeWriteQueue();
		setActiveWriteQueue(writes);
		beginRandomScope(level);

		try {
			TickOutcome outcome = CHUNK_TICKER.tickBlockEntities(plan, grid, chunkXs, chunkZs,
					identity(safe.length), i -> {
						net.minecraft.world.level.block.entity.TickingBlockEntity ticker = tickers.get(safe[i]);

						if (!ticker.isRemoved()) tick.accept(ticker);
					}, writes, failures);

			if (outcome != TickOutcome.COMPLETED) {
				for (int index : safe) {
					net.minecraft.world.level.block.entity.TickingBlockEntity ticker = tickers.get(index);

					if (!ticker.isRemoved()) tick.accept(ticker);
				}
			}
		} catch (Throwable t) {
			LOGGER.error("Parallel block entity tick for {} threw; falling back", level, t);
			GUARD.recordFallback(level.getGameTime(), t);

			for (int index : safe) {
				net.minecraft.world.level.block.entity.TickingBlockEntity ticker = tickers.get(index);

				if (!ticker.isRemoved()) tick.accept(ticker);
			}
		} finally {
			setActiveWriteQueue(previous);
			endRandomScope(level);
		}
	}

	private static int[] identity(int length) {
		int[] indices = new int[length];

		for (int i = 0; i < length; i++) {
			indices[i] = i;
		}

		return indices;
	}

	private static RegionGrid gridFor(int[] chunkXs, int[] chunkZs, int size) {
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int i = 0; i < chunkXs.length; i++) {
			minX = Math.min(minX, chunkXs[i]);
			maxX = Math.max(maxX, chunkXs[i]);
			minZ = Math.min(minZ, chunkZs[i]);
			maxZ = Math.max(maxZ, chunkZs[i]);
		}

		return new RegionGrid(minX, minZ, maxX, maxZ, size);
	}
}
