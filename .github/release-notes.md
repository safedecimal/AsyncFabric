# AsyncFabric

AsyncFabric is a fork of Fabric: the loader and the API modules, rebuilt around parallel
execution. Minecraft **26.3**, Java **25**.

**This is not a drop-in replacement for Fabric.** Mods are compiled against `net.asyncfabric.*`
and their metadata is `asyncfabric.mod.json`; a jar that only ships `fabric.mod.json` is
rejected on purpose. Existing Fabric mods will not load.

---

## Files in this release

| File | What it is |
| --- | --- |
| `asyncfabric-loader-<version>.jar` | the loader. Executable: `Main-Class` is `AsyncFabricServerLauncher` |
| `asyncfabric-loader-<version>.json` | installer metadata, listing the libraries the loader needs |
| `asyncfabric-loader-<version>.launchwrapper.json` | the same metadata for the legacy LaunchWrapper route |
| `asyncfabric-api-<version>.jar` | the API, with every module jar nested inside |

The loader reads its libraries (Mixin, ASM, …) out of the `.json` next to it, so keep that file
beside the jar. Sources and javadoc jars are not published here; they are build intermediates.

Do **not** also copy the individual `asyncfabric-api-base-*.jar`,
`asyncfabric-networking-api-v1-*.jar`, … files from a local build: they are already inside
`asyncfabric-api-*.jar` and duplicating them makes the loader reject the jar.

---

## Server

1. Put `asyncfabric-loader-*.jar` and `asyncfabric-api-*.jar` in a folder.
2. Create `eula.txt` with `eula=true`.
3. Run:

```bash
java -jar asyncfabric-loader-<version>.jar --nogui
```

The loader resolves its own libraries from `asyncfabric-loader-<version>.json`; if you run
offline, place those jars next to it.

## Client

The loader jar is not a full launcher profile on its own. The supported routes are:

- **Installer**: use the installer JSON from the loader's `build/libs` (`*-installer.json` if
  published alongside a release) with a Fabric-compatible installer, which writes the profile.
- **Manual profile**: add the loader jar to the classpath and set the main class to
  `net.asyncfabric.loader.impl.launch.knot.KnotClient`, with the libraries from
  `asyncfabric-loader-<version>.json` on the classpath as well.

Either way `asyncfabric-api-*.jar` goes into `mods/`. AsyncFabric mods, not Fabric ones, go
into the same folder.

---

## Verifying it works

Startup logs:

```
Loading Minecraft 26.3 with AsyncFabric Loader <version>
```

During play, entity, block and block-entity ticks run on threads named
`AsyncFabric-Event-N` and `AsyncFabric-Worker-N`.

### Flags

| Flag | Effect |
| --- | --- |
| `-Dasyncfabric.world.parallel=false` | forces vanilla single-threaded world ticking |
| `-Dasyncfabric.world.debug=true` | logs every parallel tick: entity count, chunk count, deferred writes, outcome |
| `-Dasyncfabric.events.threads=N` | event pool size (default `min(4, cores)`) |
| `-Dasyncfabric.async.threads=N` | async-api pool size |
| `-Dasyncfabric.loader.threads=N` | loader startup pool size |

If parallel world ticking hits a problem it logs it, retries that tick sequentially, and after
three consecutive failures disables itself for the session. A world is never left half-ticked;
`FORK.md` documents the full safety model.

---

## Building from source

Two independent Gradle builds. The API resolves the loader from `mavenLocal()`, so the loader
must be published first:

```bash
cd AsyncFabricLoader
./gradlew build publishToMavenLocal -x javadoc

cd ../AsyncFabricApi
./gradlew build -x javadoc
```

A clean API build takes roughly 10 minutes: Loom remaps a Minecraft jar for each of the 48
modules. Building the whole API and a loader build at the same time on a small machine can make
the Gradle daemon run out of memory; the build then fails with `cannot find symbol` for members
the classtweakers were meant to expose, which looks like a broken access widener rather than the
resource problem it is. Build them one at a time, or give the daemon more heap.

## What is different from Fabric

- **Events run in parallel by default.** All 294 events dispatch through `EventDispatchEngine`;
  listeners are expected to be thread-safe with no opt-in. Phase boundaries stay hard barriers.
  Rendering, screen and particle events (30 interfaces) are marked
  `@EventDispatch(MAIN_THREAD)` and stay on the game thread.
- **World ticking is parallel.** Entities, block random ticks and safe block entities tick over
  disjoint regions, with exclusive ownership, interaction grouping, deferred cross-region
  writes, and a fallback to vanilla. Hoppers, comparators, pistons and unknown mod block
  entities stay sequential.
- **Entrypoints run on the loader pool** and must be thread-safe.

## License

Apache-2.0, same as the Fabric projects this is derived from.
