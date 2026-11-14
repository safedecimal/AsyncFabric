# AsyncFabric

Fork of [Fabric](https://fabricmc.net) split into two projects:

| Project | Upstream | Purpose |
| --- | --- | --- |
| [`AsyncFabricLoader/`](AsyncFabricLoader) | [FabricMC/fabric-loader](https://github.com/FabricMC/fabric-loader) | Mod loader. Artifact `net.asyncfabric:asyncfabric-loader` |
| [`AsyncFabricApi/`](AsyncFabricApi) | [FabricMC/fabric-api](https://github.com/FabricMC/fabric-api) (branch `26.3`) | API modules. Group `net.asyncfabric.asyncfabric-api` |

Minecraft target for the API is **26.3** (`minecraft_version=26.3` in both
`gradle.properties` and the upstream branch the API was cloned from).

## What is rebranded

The fork is a full rebrand — namespaces, mod ids and Maven coordinates:

| | Upstream | AsyncFabric |
| --- | --- | --- |
| Java packages | `net.fabricmc.loader`, `net.fabricmc.fabric.api`, `net.fabricmc.api` | `net.asyncfabric.loader`, `net.asyncfabric.api` |
| Loader mod id | `fabricloader` | `asyncfabricloader` (declares `provides: ["fabricloader"]`) |
| Loader artifact | `net.fabricmc:fabric-loader` | `net.asyncfabric:asyncfabric-loader` |
| API group | `net.fabricmc.fabric-api` | `net.asyncfabric.asyncfabric-api` |
| API modules | `fabric-api-base`, `fabric-networking-api-v1`, … | `asyncfabric-api-base`, `asyncfabric-networking-api-v1`, … |
| Gradle plugins | `fabric-api.module` | `asyncfabric-api.module` |
| Version | `0.20.1` | `0.20.1+async` (`+async.local` outside CI) |

Deliberately **not** renamed, because they are contracts with external tooling
rather than branding:

- `fabric.mod.json` — no longer read. AsyncFabric mods use `asyncfabric.mod.json`;
  a jar shipping only `fabric.mod.json` is rejected as a wrong mod loader.
- `fabric.*` system properties (`fabric.development`, `fabric.side`, …) — injected by Loom.
- `fabric-api:module-lifecycle` — custom metadata key read by build tooling.
- `fabric-installer.json` / `fabric-server-launcher.properties` — installer contracts.
- `Fabric-Loom-*` manifest attributes.
- Third-party namespaces: `net.fabricmc.loom`, `mappingio`, `tinyremapper`,
  `classtweaker`, `net.fabricmc:sponge-mixin`, `mixinextras-fabric`.

Because the loader declares `provides: ["fabricloader"]`, third-party libraries
that declare a `fabricloader` dependency (MixinExtras, for example) still
resolve.

## Wrong mod loader handling

Mods in the `mods` folder must ship `asyncfabric.mod.json`. If one does not:

- **Regular Fabric mod** (`fabric.mod.json` only) or **foreign loader mod**
  (Forge `META-INF/mods.toml`, NeoForge `META-INF/neoforge.mods.toml`, Quilt
  `quilt.mod.json`, Rift, LiteLoader) → the game refuses to start and the
  AsyncFabric error GUI shows:

  - heading: `Oops, Wrong ModLoader buddy!`
  - sub-heading: `You trying to start <loader> mod in AsyncFabric! AsyncFabric doesn't support <loader> mods`
  - details tab: which mod file belongs to which loader

- **Mod with an AsyncFabric manifest but also foreign metadata** → it loads
  normally. If the game crashes, the error heading gets ` + Wrong ModLoader`
  appended and the details list the offending mod.

Plain library jars with no loader metadata are still ignored silently.

Jars loaded from the class path (bundled dependencies such as MixinExtras, which
ships `fabric.mod.json`) are still accepted through the legacy manifest, because
they are dependencies rather than mods the player installed.

`fabric.mod.json` is emitted into build output only, as a shim for upstream Loom
(`JarNester`, `RemapJarTask` hardcode that name). Sources carry only
`asyncfabric.mod.json`.

## Building

Requires JDK 21+ (loader) / JDK 25 (API, `release = 25`). JDK 25 is installed here.

Loaders must be published locally first — the API consumes it from `mavenLocal()`:

```bash
cd AsyncFabricLoader
./gradlew build publishToMavenLocal -x javadoc

cd ../AsyncFabricApi
./gradlew build -x javadoc
```

Artifacts:

- `AsyncFabricLoader/build/libs/asyncfabric-loader-<version>-signed.jar`
- `AsyncFabricApi/build/libs/asyncfabric-api-<version>.jar` (aggregate, nests all modules)

## Syncing with upstream

Each project keeps the original repository as the `upstream` remote and holds
fork work on the `asyncfabric` branch:

```bash
cd AsyncFabricLoader
git fetch upstream
git rebase upstream/master

cd ../AsyncFabricApi
git fetch upstream
git rebase upstream/26.3
```

Expect conflicts on package moves. The migration is scripted in
`_tools/rebrand.py` (loader) and `_tools/rebrand_api.py` (API); re-running them
after a rebase is the fastest way to re-apply the rename. `_tools/sort_imports.py`
re-fixes checkstyle import grouping after packages move. See `FORK.md` for the
conflict-resolution drill and the full list of protected contracts.

## Javadoc

`build` includes a javadoc task that fetches `docs.oracle.com` link targets.
In this environment that fails, so both projects are built with `-x javadoc`.
Jars for IDE/dev use are unaffected.

## Roadmap

Rebranding is complete and both projects build. The async work is the next
phase and is **not implemented yet**:

- Async mod discovery/resolution in the loader (`ModDiscoverer` already runs
  discovery on a `ForkJoinPool`; resolution and entrypoint init are sequential).
- Async entrypoints and an async event bus in the API.

## License

Apache License 2.0, as upstream. See `AsyncFabricLoader/LICENSE` and
`AsyncFabricApi/LICENSE`.
