/*
 * Copyright 2016 FabricMC
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

package net.asyncfabric.loader.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jetbrains.annotations.VisibleForTesting;
import org.objectweb.asm.Opcodes;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;

import net.asyncfabric.api.EnvType;
import net.asyncfabric.loader.api.LanguageAdapter;
import net.asyncfabric.loader.api.MappingResolver;
import net.asyncfabric.loader.api.ModContainer;
import net.asyncfabric.loader.api.ObjectShare;
import net.asyncfabric.loader.api.entrypoint.EntrypointContainer;
import net.asyncfabric.loader.impl.discovery.ArgumentModCandidateFinder;
import net.asyncfabric.loader.impl.discovery.ClasspathModCandidateFinder;
import net.asyncfabric.loader.impl.discovery.DirectoryModCandidateFinder;
import net.asyncfabric.loader.impl.discovery.ModCandidateImpl;
import net.asyncfabric.loader.impl.discovery.ModDiscoverer;
import net.asyncfabric.loader.impl.discovery.WrongModLoaderInfo;
import net.asyncfabric.loader.impl.discovery.ModResolutionException;
import net.asyncfabric.loader.impl.discovery.ModResolver;
import net.asyncfabric.loader.impl.discovery.RuntimeModRemapper;
import net.asyncfabric.loader.impl.entrypoint.EntrypointStorage;
import net.asyncfabric.loader.impl.game.GameProvider;
import net.asyncfabric.loader.impl.launch.AsyncFabricLauncherBase;
import net.asyncfabric.loader.impl.launch.MappingConfiguration;
import net.asyncfabric.loader.impl.launch.knot.Knot;
import net.asyncfabric.loader.impl.metadata.DependencyOverrides;
import net.asyncfabric.loader.impl.metadata.EntrypointMetadata;
import net.asyncfabric.loader.impl.metadata.LoaderModMetadata;
import net.asyncfabric.loader.impl.metadata.VersionOverrides;
import net.asyncfabric.loader.impl.util.AsyncExecutor;
import net.asyncfabric.loader.impl.util.DefaultLanguageAdapter;
import net.asyncfabric.loader.impl.util.ExceptionUtil;
import net.asyncfabric.loader.impl.util.LoaderUtil;
import net.asyncfabric.loader.impl.util.SystemProperties;
import net.asyncfabric.loader.impl.util.log.Log;
import net.asyncfabric.loader.impl.util.log.LogCategory;

@SuppressWarnings("deprecation")
public final class AsyncFabricLoaderImpl extends net.asyncfabric.loader.AsyncFabricLoader {
	public static final AsyncFabricLoaderImpl INSTANCE = InitHelper.get();

	public static final int ASM_VERSION = Opcodes.ASM9;

	public static final String VERSION = "0.20.1";
	public static final String MOD_ID = "asyncfabricloader";

	public static final String CACHE_DIR_NAME = ".asyncfabric"; // relative to game dir
	private static final String PROCESSED_MODS_DIR_NAME = "processedMods"; // relative to cache dir
	public static final String REMAPPED_JARS_DIR_NAME = "remappedJars"; // relative to cache dir
	private static final String TMP_DIR_NAME = "tmp"; // relative to cache dir

	protected final Map<String, ModContainerImpl> modMap = new HashMap<>();
	private List<ModCandidateImpl> modCandidates;
	protected List<ModContainerImpl> mods = new ArrayList<>();
	// mods rejected because they are not AsyncFabric mods (regular Fabric or foreign loader)
	private final List<WrongModLoaderInfo> wrongModLoaderMods = new ArrayList<>();
	// AsyncFabric mods that also ship foreign loader metadata; flagged only if the game crashes
	private final List<WrongModLoaderInfo> foreignMetadataMods = new ArrayList<>();

	private final Map<String, LanguageAdapter> adapterMap = new HashMap<>();
	private final EntrypointStorage entrypointStorage = new EntrypointStorage();
	private final ClassTweaker classTweaker = ClassTweaker.newInstance();

	private final ObjectShare objectShare = new ObjectShareImpl();

	private boolean frozen = false;

	private Object gameInstance;

	private MappingResolver mappingResolver;
	private GameProvider provider;
	private Path gameDir;
	private Path configDir;
	private DependencyOverrides dependencyOverrides;

	private AsyncFabricLoaderImpl() { }

	/**
	 * Freeze the AsyncFabricLoader, preventing additional mods from being loaded.
	 */
	public void freeze() {
		if (frozen) {
			throw new IllegalStateException("Already frozen!");
		}

		frozen = true;
		finishModLoading();
	}

	public GameProvider getGameProvider() {
		if (provider == null) throw new IllegalStateException("game provider not set (yet)");

		return provider;
	}

	public GameProvider tryGetGameProvider() {
		return provider;
	}

	public DependencyOverrides getDependencyOverrides() {
		return dependencyOverrides;
	}

	public void setGameProvider(GameProvider provider) {
		this.provider = provider;

		setGameDir(provider.getLaunchDirectory());
	}

	private void setGameDir(Path gameDir) {
		this.gameDir = gameDir.toAbsolutePath().normalize();
		this.configDir = gameDir.resolve("config");
	}

	@Override
	public String getRawGameVersion() {
		return provider.getRawGameVersion();
	}

	@Override
	public Object getGameInstance() {
		return gameInstance;
	}

	@Override
	public EnvType getEnvironmentType() {
		return AsyncFabricLauncherBase.getLauncher().getEnvironmentType();
	}

	/**
	 * @return The game instance's root directory.
	 */
	@Override
	public Path getGameDir() {
		if (gameDir == null) throw new IllegalStateException("invoked too early?");

		return gameDir;
	}

	@Override
	@Deprecated
	public File getGameDirectory() {
		return getGameDir().toFile();
	}

	/**
	 * @return The game instance's configuration directory.
	 */
	@Override
	public Path getConfigDir() {
		if (!Files.exists(configDir)) {
			try {
				Files.createDirectories(configDir);
			} catch (IOException e) {
				throw new RuntimeException("Creating config directory", e);
			}
		}

		return configDir;
	}

	@Override
	@Deprecated
	public File getConfigDirectory() {
		return getConfigDir().toFile();
	}

	public void load() {
		if (provider == null) throw new IllegalStateException("game provider not set");
		if (frozen) throw new IllegalStateException("Frozen - cannot load additional mods!");

		try {
			setup();
		} catch (ModResolutionException exception) {
			if (exception.getCause() == null) {
				throw FormattedException.ofLocalized("exception.incompatible", exception.getMessage());
			} else {
				throw FormattedException.ofLocalized("exception.incompatible", exception);
			}
		}
	}

	private void setup() throws ModResolutionException {
		boolean remapRegularMods = isDevelopmentEnvironment();
		VersionOverrides versionOverrides = new VersionOverrides();
		DependencyOverrides depOverrides = dependencyOverrides = new DependencyOverrides(configDir);

		// discover mods

		ModDiscoverer discoverer = new ModDiscoverer(versionOverrides, depOverrides);
		// mods the player installed must be AsyncFabric mods; class path jars may stay legacy
		discoverer.setUserModsDirectory(getModsDirectory0());
		discoverer.addCandidateFinder(new ClasspathModCandidateFinder());
		discoverer.addCandidateFinder(new DirectoryModCandidateFinder(getModsDirectory0(), remapRegularMods));
		discoverer.addCandidateFinder(new ArgumentModCandidateFinder(remapRegularMods));

		Map<String, Set<ModCandidateImpl>> envDisabledMods = new HashMap<>();
		modCandidates = discoverer.discoverMods(this, envDisabledMods);

		wrongModLoaderMods.addAll(discoverer.getWrongModLoaderMods());
		foreignMetadataMods.addAll(discoverer.getForeignMetadataMods());

		// refuse to start when regular Fabric or foreign loader mods are present:
		// they would silently do nothing, which is worse than a clear error
		if (!wrongModLoaderMods.isEmpty()) {
			throw buildWrongModLoaderException();
		}

		// dump version and dependency overrides info

		if (!versionOverrides.getAffectedModIds().isEmpty()) {
			Log.info(LogCategory.GENERAL, "Versions overridden for %s", String.join(", ", versionOverrides.getAffectedModIds()));
		}

		if (!depOverrides.getAffectedModIds().isEmpty()) {
			Log.info(LogCategory.GENERAL, "Dependencies overridden for %s", String.join(", ", depOverrides.getAffectedModIds()));
		}

		// resolve mods

		modCandidates = ModResolver.resolve(modCandidates, getEnvironmentType(), envDisabledMods);

		dumpModList(modCandidates);
		dumpNonAsyncFabricMods(discoverer.getNonAsyncFabricMods());

		Path cacheDir = gameDir.resolve(CACHE_DIR_NAME);
		Path outputdir = cacheDir.resolve(PROCESSED_MODS_DIR_NAME);

		// runtime mod remapping

		if (remapRegularMods) {
			if (System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) == null) {
				MappingConfiguration config = AsyncFabricLauncherBase.getLauncher().getMappingConfiguration();

				if (!config.getRuntimeNamespace().equals(config.getDefaultModDistributionNamespace())) {
					Log.warn(LogCategory.MOD_REMAP, "Runtime mod remapping disabled due to no asyncfabric.remapClasspathFile being specified. You may need to update loom.");
				}
			} else {
				RuntimeModRemapper.remap(modCandidates, cacheDir.resolve(TMP_DIR_NAME), outputdir);
			}
		}

		// shuffle mods in-dev to reduce the risk of false order reliance, apply late load requests

		if (isDevelopmentEnvironment() && !SystemProperties.isSet(SystemProperties.DEBUG_DISABLE_MOD_SHUFFLE)) {
			Collections.shuffle(modCandidates);
		}

		String modsToLoadLate = System.getProperty(SystemProperties.DEBUG_LOAD_LATE);

		if (modsToLoadLate != null) {
			for (String modId : modsToLoadLate.split(",")) {
				for (Iterator<ModCandidateImpl> it = modCandidates.iterator(); it.hasNext(); ) {
					ModCandidateImpl mod = it.next();

					if (mod.getId().equals(modId)) {
						it.remove();
						modCandidates.add(mod);
						break;
					}
				}
			}
		}

		// add mods

		for (ModCandidateImpl mod : modCandidates) {
			if (!mod.hasPath() && !mod.isBuiltin()) {
				try {
					mod.setPaths(Collections.singletonList(mod.copyToDir(outputdir, false)));
				} catch (IOException e) {
					throw new RuntimeException("Error extracting mod "+mod, e);
				}
			}

			addMod(mod);
		}

		modCandidates = null;
	}

	@VisibleForTesting
	public void dumpNonAsyncFabricMods(List<Path> nonAsyncFabricMods) {
		if (nonAsyncFabricMods.isEmpty()) return;
		StringBuilder outputText = new StringBuilder();

		for (Path nonAsyncFabricMod : nonAsyncFabricMods) {
			outputText.append("\n\t- ").append(nonAsyncFabricMod.getFileName());
		}

		int modsCount = nonAsyncFabricMods.size();
		Log.warn(LogCategory.GENERAL, "Found %d non-asyncfabric mod%s:%s", modsCount, modsCount != 1 ? "s" : "", outputText);
	}

	/** @return mods rejected at startup because they are not AsyncFabric mods. */
	public List<WrongModLoaderInfo> getWrongModLoaderMods() {
		return Collections.unmodifiableList(wrongModLoaderMods);
	}

	/** @return AsyncFabric mods that also ship foreign loader metadata. */
	public List<WrongModLoaderInfo> getForeignMetadataMods() {
		return Collections.unmodifiableList(foreignMetadataMods);
	}

	/**
	 * Builds the startup error shown when regular Fabric or foreign loader mods
	 * are present. One of them cannot work here, so the game refuses to start.
	 */
	private FormattedException buildWrongModLoaderException() {
		String mainText = "Oops, Wrong ModLoader buddy!";

		String subText;

		if (wrongModLoaderMods.size() == 1) {
			WrongModLoaderInfo info = wrongModLoaderMods.get(0);
			subText = String.format(Locale.ENGLISH,
					"You trying to start %s mod in AsyncFabric! AsyncFabric doesn't support %s mods",
					loaderNameForSentence(info), loaderNameForSentence(info));
		} else {
			subText = "You trying to start mods from other mod loaders in AsyncFabric! AsyncFabric doesn't support mods from other mod loaders";
		}

		FormattedException ret = new FormattedException(mainText, buildWrongModLoaderDetails());
		ret.setSubText(subText);

		return ret;
	}

	private String loaderNameForSentence(WrongModLoaderInfo info) {
		// "regular fabric mod" reads better than "Fabric mod" in the sentence
		if (info.getKind() == WrongModLoaderInfo.Kind.REGULAR_FABRIC) {
			return "regular fabric";
		}

		return info.getLoaderName();
	}

	/**
	 * Builds the crash-details body: which mods are from which loader. This text
	 * lands in the expandable details area of the error gui.
	 */
	private String buildWrongModLoaderDetails() {
		StringBuilder ret = new StringBuilder();

		Map<String, List<String>> byLoader = new LinkedHashMap<>();

		for (WrongModLoaderInfo info : wrongModLoaderMods) {
			byLoader.computeIfAbsent(info.getLoaderName(), k -> new ArrayList<>()).add(info.getModFileName());
		}

		ret.append("These mods could not be loaded because they are not AsyncFabric mods:");

		for (Map.Entry<String, List<String>> entry : byLoader.entrySet()) {
			ret.append('\n').append('\n').append(entry.getKey()).append(" mods:");

			for (String fileName : entry.getValue()) {
				ret.append("\n\t- ").append(fileName);
			}
		}

		ret.append("\n\nAsyncFabric needs asyncfabric.mod.json in every mod. Remove the mods listed above, "
				+ "or use a build of them made for AsyncFabric.");

		return ret.toString();
	}

	private void dumpModList(List<ModCandidateImpl> mods) {
		StringBuilder modListText = new StringBuilder();

		boolean[] lastItemOfNestLevel = new boolean[mods.size()];
		List<ModCandidateImpl> topLevelMods = mods.stream()
				.filter(mod -> mod.getParentMods().isEmpty())
				.collect(Collectors.toList());
		int topLevelModsCount = topLevelMods.size();

		for (int i = 0; i < topLevelModsCount; i++) {
			boolean lastItem = i == topLevelModsCount - 1;

			if (lastItem) lastItemOfNestLevel[0] = true;

			dumpModList0(topLevelMods.get(i), modListText, 0, lastItemOfNestLevel);
		}

		int modsCount = mods.size();
		Log.info(LogCategory.GENERAL, "Loading %d mod%s:%n%s", modsCount, modsCount != 1 ? "s" : "", modListText);
	}

	private void dumpModList0(ModCandidateImpl mod, StringBuilder log, int nestLevel, boolean[] lastItemOfNestLevel) {
		if (log.length() > 0) log.append('\n');

		for (int depth = 0; depth < nestLevel; depth++) {
			log.append(depth == 0 ? "\t" : lastItemOfNestLevel[depth] ? "     " : "   | ");
		}

		log.append(nestLevel == 0 ? "\t" : "  ");
		log.append(nestLevel == 0 ? "-" : lastItemOfNestLevel[nestLevel] ? " \\--" : " |--");
		log.append(' ');
		log.append(mod.getId());
		log.append(' ');
		log.append(mod.getVersion().getFriendlyString());

		List<ModCandidateImpl> nestedMods = new ArrayList<>(mod.getNestedMods());
		nestedMods.sort(Comparator.comparing(nestedMod -> nestedMod.getMetadata().getId()));

		if (!nestedMods.isEmpty()) {
			Iterator<ModCandidateImpl> iterator = nestedMods.iterator();
			ModCandidateImpl nestedMod;
			boolean lastItem;

			while (iterator.hasNext()) {
				nestedMod = iterator.next();
				lastItem = !iterator.hasNext();

				if (lastItem) lastItemOfNestLevel[nestLevel+1] = true;

				dumpModList0(nestedMod, log, nestLevel + 1, lastItemOfNestLevel);

				if (lastItem) lastItemOfNestLevel[nestLevel+1] = false;
			}
		}
	}

	private void finishModLoading() {
		// add mods to classpath
		// TODO: This can probably be made safer, but that's a long-term goal
		for (ModContainerImpl mod : mods) {
			if (!mod.getMetadata().getId().equals(MOD_ID) && !mod.getMetadata().getType().equals("builtin")) {
				for (Path path : mod.getCodeSourcePaths()) {
					AsyncFabricLauncherBase.getLauncher().addToClassPath(path);
				}
			}
		}

		setupLanguageAdapters();
		setupMods();
	}

	public boolean hasEntrypoints(String key) {
		return entrypointStorage.hasEntrypoints(key);
	}

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return entrypointStorage.getEntrypoints(key, type);
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return entrypointStorage.getEntrypointContainers(key, type);
	}

	/**
	 * Invokes an entrypoint stage across all mods, in parallel.
	 *
	 * <p>AsyncFabric is its own platform: mods target it directly instead of being ported
	 * unchanged from Fabric, so entrypoints are expected to be thread-safe and must not assume
	 * an ordering between mods. Every entrypoint of the stage runs concurrently on the loader
	 * pool and the method returns only after all of them finished, so later stages still see a
	 * fully initialised platform.
	 *
	 * <p>Failures are collected per entrypoint and rethrown together once every entrypoint
	 * finished, so one broken mod cannot abandon the rest half-initialised.
	 */
	@Override
	public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
		if (!hasEntrypoints(key)) {
			Log.debug(LogCategory.ENTRYPOINT, "No subscribers for entrypoint '%s'", key);
			return;
		}

		List<EntrypointContainer<T>> entrypoints = AsyncFabricLoaderImpl.INSTANCE.getEntrypointContainers(key, type);
		if (entrypoints.isEmpty()) return;

		Log.debug(LogCategory.ENTRYPOINT, "Invoking entrypoint '%s' in parallel across %d mods", key, entrypoints.size());

		invokeEntrypointsParallel(key, entrypoints, invoker);
	}

	/**
	 * Runs the invoker for every container concurrently, then rethrows collected failures.
	 *
	 * <p>Split out from {@link #invokeEntrypoints} so the dispatch logic can be tested without
	 * bootstrapping a loader instance.
	 */
	static <T> void invokeEntrypointsParallel(String key, List<? extends EntrypointContainer<T>> entrypoints, Consumer<? super T> invoker) {
		// instantiate on the calling thread: entrypoint construction is not the work being
		// parallelised, and keeping it here preserves the original error attribution
		List<T> instances = new ArrayList<>(entrypoints.size());

		for (EntrypointContainer<T> container : entrypoints) {
			instances.add(container.getEntrypoint());
		}

		ForkJoinPool pool = AsyncExecutor.sharedPool();
		List<CompletableFuture<Void>> tasks = new ArrayList<>(instances.size());

		for (T instance : instances) {
			tasks.add(CompletableFuture.runAsync(() -> invoker.accept(instance), pool));
		}

		// wait in submission order so the reported failure order stays stable
		RuntimeException exception = null;

		for (int i = 0; i < tasks.size(); i++) {
			EntrypointContainer<T> container = entrypoints.get(i);

			try {
				tasks.get(i).join();
			} catch (CompletionException e) {
				exception = ExceptionUtil.gatherExceptions(e.getCause(),
						exception,
						exc -> new RuntimeException(String.format("Could not execute entrypoint stage '%s' due to errors, provided by '%s' at '%s'!",
								key, container.getProvider().getMetadata().getId(), container.getDefinition()),
								exc));
			}
		}

		if (exception != null) {
			throw exception;
		}
	}

	@Override
	public MappingResolver getMappingResolver() {
		if (mappingResolver == null) {
			MappingConfiguration config = AsyncFabricLauncherBase.getLauncher().getMappingConfiguration();
			String runtimeNamespace = config.getRuntimeNamespace();

			mappingResolver = new LazyMappingResolver(() -> new MappingResolverImpl(config.getMappings(), runtimeNamespace),
					runtimeNamespace);
		}

		return mappingResolver;
	}

	@Override
	public ObjectShare getObjectShare() {
		return objectShare;
	}

	public ModCandidateImpl getModCandidate(String id) {
		if (modCandidates == null) return null;

		for (ModCandidateImpl mod : modCandidates) {
			if (mod.getId().equals(id)) return mod;
		}

		return null;
	}

	@Override
	public Optional<net.asyncfabric.loader.api.ModContainer> getModContainer(String id) {
		return Optional.ofNullable(modMap.get(id));
	}

	@Override
	public Collection<ModContainer> getAllMods() {
		return Collections.unmodifiableList(mods);
	}

	public List<ModContainerImpl> getModsInternal() {
		return mods;
	}

	@Override
	public boolean isModLoaded(String id) {
		return modMap.containsKey(id);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return AsyncFabricLauncherBase.getLauncher().isDevelopment();
	}

	private void addMod(ModCandidateImpl candidate) throws ModResolutionException {
		ModContainerImpl container = new ModContainerImpl(candidate);
		mods.add(container);
		modMap.put(candidate.getId(), container);

		for (String provides : candidate.getProvides()) {
			modMap.put(provides, container);
		}
	}

	private void setupLanguageAdapters() {
		adapterMap.put("default", DefaultLanguageAdapter.INSTANCE);

		for (ModContainerImpl mod : mods) {
			// add language adapters
			for (Map.Entry<String, String> laEntry : mod.getInfo().getLanguageAdapterDefinitions().entrySet()) {
				if (adapterMap.containsKey(laEntry.getKey())) {
					throw new RuntimeException("Duplicate language adapter key: " + laEntry.getKey() + "! (" + laEntry.getValue() + ", " + adapterMap.get(laEntry.getKey()).getClass().getName() + ")");
				}

				try {
					adapterMap.put(laEntry.getKey(), (LanguageAdapter) Class.forName(laEntry.getValue(), true, AsyncFabricLauncherBase.getLauncher().getTargetClassLoader()).getDeclaredConstructor().newInstance());
				} catch (Exception e) {
					throw new RuntimeException("Failed to instantiate language adapter: " + laEntry.getKey(), e);
				}
			}
		}
	}

	private void setupMods() {
		for (ModContainerImpl mod : mods) {
			try {
				for (String in : mod.getInfo().getOldInitializers()) {
					String adapter = mod.getInfo().getOldStyleLanguageAdapter();
					entrypointStorage.addDeprecated(mod, adapter, in);
				}

				for (String key : mod.getInfo().getEntrypointKeys()) {
					for (EntrypointMetadata in : mod.getInfo().getEntrypoints(key)) {
						entrypointStorage.add(mod, key, in, adapterMap);
					}
				}
			} catch (Exception e) {
				throw new RuntimeException(String.format("Failed to setup mod %s (%s)", mod.getInfo().getName(), mod.getOrigin()), e);
			}
		}
	}

	public void loadClassTweakers() {
		ClassTweakerReader ctReader = ClassTweakerReader.create(classTweaker);

		for (net.asyncfabric.loader.api.ModContainer modContainer : getAllMods()) {
			LoaderModMetadata modMetadata = (LoaderModMetadata) modContainer.getMetadata();
			String location = modMetadata.getClassTweaker();
			if (location == null) continue;

			Path path = modContainer.findPath(location).orElse(null);
			if (path == null) throw new RuntimeException(String.format("Missing classTweaker file %s from mod %s", location, modContainer.getMetadata().getId()));

			try (BufferedReader reader = Files.newBufferedReader(path)) {
				ctReader.read(reader, AsyncFabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace());
			} catch (Exception e) {
				throw new RuntimeException("Failed to read classTweaker file from mod " + modMetadata.getId(), e);
			}
		}
	}

	public void prepareModInit(Path newRunDir, Object gameInstance) {
		if (!frozen) {
			throw new RuntimeException("Cannot instantiate mods when not frozen!");
		}

		if (gameInstance != null && AsyncFabricLauncherBase.getLauncher() instanceof Knot) {
			ClassLoader gameClassLoader = gameInstance.getClass().getClassLoader();
			ClassLoader targetClassLoader = AsyncFabricLauncherBase.getLauncher().getTargetClassLoader();
			boolean matchesKnot = (gameClassLoader == targetClassLoader);
			boolean containsKnot = false;

			if (matchesKnot) {
				containsKnot = true;
			} else {
				gameClassLoader = gameClassLoader.getParent();

				while (gameClassLoader != null && gameClassLoader.getParent() != gameClassLoader) {
					if (gameClassLoader == targetClassLoader) {
						containsKnot = true;
					}

					gameClassLoader = gameClassLoader.getParent();
				}
			}

			if (!matchesKnot) {
				if (containsKnot) {
					Log.info(LogCategory.KNOT, "Environment: Target class loader is parent of game class loader.");
				} else {
					Log.warn(LogCategory.KNOT, "\n\n* CLASS LOADER MISMATCH! THIS IS VERY BAD AND WILL PROBABLY CAUSE WEIRD ISSUES! *\n"
							+ " - Expected game class loader: %s\n"
							+ " - Actual game class loader: %s\n"
							+ "Could not find the expected class loader in game class loader parents!\n",
							AsyncFabricLauncherBase.getLauncher().getTargetClassLoader(), gameClassLoader);
				}
			}
		}

		this.gameInstance = gameInstance;

		if (gameDir != null) {
			try {
				if (!gameDir.toRealPath().equals(newRunDir.toRealPath())) {
					Log.warn(LogCategory.GENERAL, "Inconsistent game execution directories: engine says %s, while initializer says %s...",
							newRunDir.toRealPath(), gameDir.toRealPath());
					setGameDir(newRunDir);
				}
			} catch (IOException e) {
				Log.warn(LogCategory.GENERAL, "Exception while checking game execution directory consistency!", e);
			}
		} else {
			setGameDir(newRunDir);
		}
	}

	public ClassTweaker getClassTweaker() {
		return classTweaker;
	}

	/**
	 * Sets the game instance. This is only used in 20w22a+ by the dedicated server and should not be called by anything else.
	 */
	public void setGameInstance(Object gameInstance) {
		if (getEnvironmentType() != EnvType.SERVER) {
			throw new UnsupportedOperationException("Cannot set game instance on a client!");
		}

		if (this.gameInstance != null) {
			throw new UnsupportedOperationException("Cannot overwrite current game instance!");
		}

		this.gameInstance = gameInstance;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return getGameProvider().getLaunchArguments(sanitize);
	}

	@Override
	protected Path getModsDirectory0() {
		String directory = System.getProperty(SystemProperties.MODS_FOLDER);

		return directory != null ? Paths.get(directory) : gameDir.resolve("mods");
	}

	/**
	 * Provides singleton for static init assignment regardless of load order.
	 */
	public static class InitHelper {
		private static AsyncFabricLoaderImpl instance;

		public static AsyncFabricLoaderImpl get() {
			if (instance == null) instance = new AsyncFabricLoaderImpl();

			return instance;
		}
	}

	static {
		LoaderUtil.verifyNotInTargetCl(AsyncFabricLoaderImpl.class);
	}
}
