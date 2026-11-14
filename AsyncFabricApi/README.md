# AsyncFabric API

Essential hooks for modding with Fabric.

AsyncFabric API is the library for essential hooks and interoperability mechanisms for AsyncFabric mods. Examples include:

- Exposing functionality that is useful but difficult to access for many mods such as particles, biomes and dimensions
- Adding events, hooks and APIs to improve interoperability between mods.
- Essential features such as registry synchronization and adding information to crash reports.
- An advanced rendering API designed for compatibility with optimization mods and graphics overhaul mods.

Also check out [AsyncFabric Loader](https://github.com/FabricMC/fabric-loader), the (mostly) version-independent mod loader that powers Fabric. AsyncFabric API is a mod like any other AsyncFabric mod which requires AsyncFabric Loader to be installed.

For support and discussion for both developers and users, visit [the AsyncFabric Discord server](https://discord.gg/v6v4pMv).

## Using AsyncFabric API to play with mods

Make sure you have installed fabric loader first. More information about installing AsyncFabric Loader can be found [here](https://fabricmc.net/use/).

To use AsyncFabric API, download it from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api), [GitHub Releases](https://github.com/FabricMC/fabric/releases) or [Modrinth](https://modrinth.com/mod/fabric-api).

The downloaded jar file should be placed in your `mods` folder.

## Using AsyncFabric API to develop mods

To set up a AsyncFabric development environment, check out the [AsyncFabric example mod](https://github.com/FabricMC/fabric-example-mod) and follow the instructions there. The example mod already depends on AsyncFabric API.

To include the full AsyncFabric API with all modules in the development environment, add the following to your `dependencies` block in the gradle buildscript:

### Groovy DSL

```groovy
implementation "net.fabricmc.fabric-api:fabric-api:FABRIC_API_VERSION"
```

### Kotlin DSL

```kotlin
implementation("net.fabricmc.fabric-api:fabric-api:FABRIC_API_VERSION")
```

Alternatively, modules from AsyncFabric API can be specified individually as shown below (including module jar to your mod jar):

### Groovy DSL

```groovy
// Make a collection of all api modules we wish to use
Set<String> apiModules = [
    "asyncfabric-api-base",
    "fabric-command-api-v1",
    "asyncfabric-lifecycle-events-v1",
    "asyncfabric-networking-api-v1"
]

// Add each module as a dependency
apiModules.forEach {
    include(implementation(fabricApi.module(it, FABRIC_API_VERSION)))
}
```

### Kotlin DSL

```kotlin
// Make a set of all api modules we wish to use
setOf(
    "asyncfabric-api-base",
    "fabric-command-api-v1",
    "asyncfabric-lifecycle-events-v1",
    "asyncfabric-networking-api-v1"
).forEach {
    // Add each module as a dependency
    implementation(fabricApi.module(it, FABRIC_API_VERSION))
}
```

<!--Linked to gradle documentation on properties-->
Instead of hardcoding version constants all over the build script, Gradle properties may be used to replace these constants. Properties are defined in the `gradle.properties` file at the root of a project. More information is available [here](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html#declare_properties_in_gradle_properties_file).

## Contributing

See something AsyncFabric API doesn't support, a bug or something that may be useful? We welcome contributions to improve AsyncFabric API.
Make sure to read [the development guidelines](./CONTRIBUTING.md).

## Modules

AsyncFabric API is designed to be modular for ease of updating. This also has the advantage of splitting up the codebase into smaller chunks.

Each module contains its own `README.md`* explaining the module's purpose and additional info on using the module.

\* The README for each module is being worked on; not every module has a README at the moment
