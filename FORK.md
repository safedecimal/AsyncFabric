# Sync with upstream

Both projects keep the original repository as the `upstream` remote and all fork
work on the `asyncfabric` branch.

```bash
cd AsyncFabricLoader && git fetch upstream && git rebase upstream/master
cd ../AsyncFabricApi && git fetch upstream && git rebase upstream/26.3
```

## Conflict triage

Upstream will touch files whose paths we moved. Two kinds of conflicts:

**1. Package/path conflicts (most common).** Upstream adds or edits
`src/main/java/net/fabricmc/...`; our tree has `net/asyncfabric/...`.

Resolution: take the upstream file, then re-apply the rename to just that path:

```bash
git checkout --theirs <path>          # or resolve the content manually
python -I ../_tools/rebrand.py .      # loader
python -I ../_tools/rebrand_api.py .  # api
python -I ../_tools/sort_imports.py .
```

Then `git mv` any newly added `net/fabricmc` directory to `net/asyncfabric`
(the scripts rewrite file contents and names, not directory placement).

**2. Genuine logic conflicts.** Upstream changed behaviour in code we also
changed. Read both sides; keep upstream's logic and our names.

## After every sync

```bash
./gradlew spotlessApply          # line endings + formatting
./gradlew build -x javadoc       # must be green before committing
```

For the loader, also republish so the API picks up the change:

```bash
cd AsyncFabricLoader && ./gradlew publishToMavenLocal -x javadoc
```

## What the scripts protect

`_tools/rebrand.py` and `_tools/rebrand_api.py` stash these before rewriting, so
re-running them never breaks the fork:

- `"fabric.*"` system property names
- `fabric-api:module-lifecycle`
- `fabric-installer*.json`, `fabric-server-launch*.properties`
- `Fabric-Loom-*` manifest attributes
- `net.fabricmc.{loom,mappingio,tinyremapper,classtweaker,installer}` and
  `net.fabricmc:sponge-mixin`, `net.fabricmc:intermediary`, `net.fabricmc:yarn`
- `mixinextras-fabric`
- upstream URLs (`maven.fabricmc.net`, `github.com/FabricMC/...`) and license
  headers (`FabricMC` copyright)

If upstream adds a new external namespace that accidentally gets renamed, add it
to the `PROTECT` regex in the relevant script.

## Version bumps

The loader version is derived from `VERSION` in
`AsyncFabricLoader/src/main/java/net/asyncfabric/loader/impl/AsyncFabricLoaderImpl.java`
plus `+async` (`+async.local` outside CI). After bumping it, update
`asyncfabric-loader` in `AsyncFabricApi/gradle/libs.versions.toml` and the
`asyncfabricloader` dependency in the API module manifests.

## Threading model

AsyncFabric runs mod code in parallel by default. This is a deliberate break with
Fabric: AsyncFabric is its own platform, mods target it directly, and a listener is
expected to be thread-safe without any opt-in.

**Events.** Every event created through `EventFactory` is dispatched in parallel.
Nothing has to be declared to get this.

- `net.asyncfabric.impl.base.event.EventDispatchEngine` is the single funnel. All
  events are built by `ArrayBackedEvent.update()`, so the dispatch decision lives
  there and no invoker lambda needs editing.
- It fills the listener array with deferring proxies. The untouched invoker loop
  then hands each listener to a worker, which is how hundreds of existing lambdas
  leave the game thread without being rewritten.
- Only `void` listeners are deferred. A listener that returns a value feeds it back
  into the loop (a cancellation flag, a transformed stack, a computed slot), so it
  stays inline and ordered.
- **Phase boundaries are hard barriers.** Phases exist to order groups of
  listeners, so the last listener of a phase waits for its phase before the loop
  moves on. Listeners *within* one phase run concurrently and their order is not
  guaranteed.
- Listener failures are collected and rethrown after the emission, the same way
  vanilla's accumulating invoker reported them.
- Nesting is safe: an event emitted from inside a listener runs inline, so a
  listener never waits on work queued behind itself.

**Opting out.** Mark a listener interface with

```java
@EventDispatch(EventThreadMode.MAIN_THREAD)
```

and its listeners run on the emitting thread, in order, exactly like vanilla.
Required for anything touching the render pipeline, the GL context, window or input
state, or world/registry mutation without an explicit hand-off. The client-side
render, screen and particle events are already marked this way — 30 interfaces across
`asyncfabric-rendering-v1`, `asyncfabric-screen-api-v1` and
`asyncfabric-particles-v1`.

**Entrypoints.** `main`/`client`/`server`/`preLaunch` initializers run on the loader
pool and must be thread-safe; the stage is joined before the next one starts.

**Pool.** `asyncfabric.events.threads` sizes the event pool (default
`min(4, cores)`); `asyncfabric.async.threads` sizes the async-api pool;
`asyncfabric.loader.threads` sizes loader startup work.

## Parallel world ticking

Minecraft's world is one big mutable graph that vanilla touches from a single thread.
AsyncFabric ticks the entities of a level in parallel over disjoint regions, so every
mod's entity tick runs off the game thread with no opt-in.

**Where.** `ServerLevelMixin` redirects the `EntityTickList.forEach` call inside
`ServerLevel.tick`. The replaced call either drives the same vanilla consumer from
several threads, or runs it exactly as vanilla does.

**The four defences against races, in order of importance:**

1. **Exclusive region ownership** (`RegionGrid`). The world is cut into regions of
   chunks; a region is claimed by exactly one thread for the duration of the tick.
   Chunks are never split between threads.
2. **Interaction closure** (`TickPlan`, `EntityGroups`). Entities that can affect each
   other — a vehicle and its passengers, chains via the root vehicle — are merged with
   a union-find into one scheduling unit, transitively, even when they sit in different
   regions. Two entities that interact can never be on two threads at once.
3. **Deferred cross-region effects** (`DeferredActions`). An entity that wants to
   change state owned by another region queues the effect instead. Effects are applied
   on the game thread at the barrier, in queue order, and carry a dedupe key so the same
   change cannot be applied twice — that is the dupe defence.
4. **Detection instead of hope** (`RaceGuard`, `TickOutcome`). A failure, timeout or
   ownership violation is logged, and the outcome records whether replaying is safe:
   `SKIPPED` means nothing advanced (replay is safe), `PARTIAL` means some entities
   already moved and replaying would tick them twice (so it does not). After three
   consecutive failures parallel ticking disables itself for the session.

**Chunk generation is already parallel in vanilla.** 26.3 ships
`ChunkGenerationTask`, `ChunkMap.worldgenTaskDispatcher`, `ChunkTaskDispatcher` and
`ThreadedLevelLightEngine`. There is nothing to parallelise there; only the pool size is
worth tuning.

## Parallel chunk and block-entity ticking

The second parallel surface. `ServerChunkCacheMixin` redirects the
`chunkMap.forEachBlockTickingChunk(consumer)` call in `ServerChunkCache.tickChunks`, and
`LevelMixin` hooks `tickBlockEntities`. Both fall back to the vanilla loop when anything
is uncertain.

**What runs in parallel:**

- Block and fluid **random ticks** (`ServerLevel.tickChunk`), partitioned by region.
- **Block entities** whose type passes `BlockEntitySafety`.

**What stays sequential, on purpose:**

- Hoppers, comparators, pistons, sticky pistons, observers, sculk sensors, daylight
  detectors, targets, beacons, conduits, lecterns — their behaviour *is* their tick order.
- Every **unknown mod block entity type**. `BlockEntitySafety.isSafe` fails closed:
  a wrong "safe" verdict corrupts a save, a wrong "unsafe" verdict only costs speed. A mod
  opts in with `BlockEntitySafety.registerSafe("mymod:type")`.
- **Redstone and neighbour updates.** Vanilla's `CollectingNeighborUpdater` keeps one
  update stack per level; sharing it between threads would make circuit behaviour
  non-deterministic.

**Three extra defences this surface needed:**

1. **Block writes are routed, not raced.** `Level.setBlock` is hooked: a write inside the
   worker's own region applies immediately; a write crossing into another region is queued
   in `BlockWriteQueue` and applied on the game thread at the barrier. Because `setBlock`
   returns a boolean the caller branches on (grow the plant, play the sound, place the
   item), the deferred call reports `true`. `BlockWriteQueue` collapses writes per position
   so a block changed twice in one tick fires one update, not two.
2. **Random sources are forked per worker.** `Level.random` is a single
   `LegacyRandomSource` and is not thread-safe; two workers drawing from it corrupt its
   seed and can return the same value twice. `LevelMixin` redirects `getRandom()` on a
   worker to a per-thread fork captured from the tick's base.
3. **Vanilla's pending-ticker merge is replicated.** `tickBlockEntities` normally moves
   `pendingBlockEntityTickers` into `blockEntityTickers` first; the replacement does the
   same, otherwise a block entity placed this tick waits a tick.

**Properties.**

- `-Dasyncfabric.world.parallel=false` forces vanilla single-threaded world ticking.
- `-Dasyncfabric.world.debug=true` logs every parallel tick (entity count, chunk count,
  deferred writes, outcome).
- `-Dasyncfabric.world.probe=true` makes the async testmod spawn probe entities and
  verify their count stays stable — the end-to-end anti-dupe check.

**Known limit.** Chunks only tick when loaded, so a dedicated server with no players
ticks almost nothing; the probe spawns near spawn and still finds zero entities
off-line. Meaningful in-world verification needs a client connected.



## Known problem: API build from a clean checkout

`./gradlew build` in `AsyncFabricApi` succeeds in a warm working copy and fails in a fresh
clone, including on CI. The failure is `cannot find symbol` for members that only exist
through a classtweaker — `addTask`, `completeTask`, `getFabricPackSource`,
`getTranslationKey`, `PoiTypes.register` — and the set of failing modules changes between
runs, so it is not a missing source file.

What has been ruled out, all by experiment:

- **Sources differ.** They do not: `diff -r` over the module trees, and `cmp` over the
  classtweakers, show byte-identical files against a copy that builds.
- **Heap.** 4G and 8G both build and both fail; the daemon disappearing was a red herring
  caused by running two builds at once.
- **Project parallelism.** `org.gradle.parallel=false`, `--no-parallel` and
  `--workers.max=1` each produced a green run once and failed on the next attempt.
- **Configuration cache.** `--no-configuration-cache` made no difference.
- **Build order.** Pre-building the classtweaker-providing modules, and running the build
  twice, both helped sometimes and not others.
- **Absolute path length.** A copy under a shorter path built; a longer one did not; not
  reproducible.

What is certain: Loom remaps one Minecraft jar per project, and the resulting artifact's
content varies with the set of classtweakers present when it is built. Everything points at
that step, but the trigger has not been isolated.

The CI workflow deliberately does not hide this: a red run means the API genuinely did not
build. The loader job passes, so the loader side is exercised on every push.
