asyncfabric-loader
==================

The mod loader for AsyncFabric — a fork of [AsyncFabric Loader](https://github.com/FabricMC/fabric-loader).
It provides mod loading facilities and useful abstractions for other mods to use.

The fork is source-compatible with AsyncFabric: the `net.fabricmc.*` packages and the `asyncfabricloader`
mod ID are unchanged, so existing AsyncFabric mods load as-is. Only the Maven coordinates
(`net.asyncfabric:asyncfabric-loader`) and the version suffix (`+async`) differ.

## Building

Requires Java 21+ to build.

```
./gradlew build
```

The mod loader jar lands in `build/libs/asyncfabric-loader-<version>-signed.jar`.

## Upstream

```
git remote add upstream https://github.com/FabricMC/fabric-loader.git
git fetch upstream
git rebase upstream/master
```

## License

Licensed under the Apache License 2.0.
