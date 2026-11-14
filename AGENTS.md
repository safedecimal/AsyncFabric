# AsyncFabric — рабочие правила

Два независимых git-репозитория, каждый форк upstream-проекта Fabric:

- `AsyncFabricLoader/` — форк [FabricMC/fabric-loader](https://github.com/FabricMC/fabric-loader), ветка `asyncfabric`, upstream-remote `upstream` (master).
- `AsyncFabricApi/` — форк [FabricMC/fabric-api](https://github.com/FabricMC/fabric-api), ветка `asyncfabric`, upstream-remote `upstream` (ветка `26.3`).

Minecraft: **26.3**. JDK: 21+ для loader, 25 для API (обе есть в системе).

## Сборка

Loader нужно публиковать локально первым — API берёт его из `mavenLocal()`:

```bash
cd AsyncFabricLoader && ./gradlew build publishToMavenLocal -x javadoc
cd ../AsyncFabricApi && ./gradlew build -x javadoc
```

`-x javadoc` обязателен: javadoc падает на сетевых ссылках Oracle/Maven в окружении без доступа к ним. Это ограничение окружения, не кода.

## Правила

1. Манифест мода — `asyncfabric.mod.json`. `fabric.mod.json` из исходников не
   читается: jar только с ним загрузчик отвергает с ошибкой «Wrong ModLoader».
   Не менять `fabric.*` системные свойства,
   `fabric-api:module-lifecycle`, `Fabric-Loom-*` атрибуты — это контракты с
   внешним тулингом, а не брендинг.
2. Не переименовывать чужие namespace'ы: `net.fabricmc.{loom,mappingio,tinyremapper,classtweaker}`,
   `net.fabricmc:sponge-mixin`, `mixinextras-fabric`.
3. Загрузчик объявляет `provides: ["fabricloader"]` — сторонние библиотеки
   (MixinExtras и др.) продолжают резолвиться. Не удалять.
4. После sync с upstream прогонять `_tools/rebrand.py`, `_tools/rebrand_api.py`,
   `_tools/sort_imports.py` (см. `README.md`, раздел Syncing).
5. Версия загрузчика держит числовую часть upstream (`0.20.1`) и добавляет
   build-metadata `+async`; semver-сравнение её игнорирует, поэтому диапазоны
   зависимостей модов остаются валидными.
6. Async-архитектура ещё не реализована — это следующий этап, не путать
   с готовой функциональностью.
