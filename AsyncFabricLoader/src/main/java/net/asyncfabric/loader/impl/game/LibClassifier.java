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

package net.asyncfabric.loader.impl.game;

import java.io.IOException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipError;
import java.util.zip.ZipFile;

import net.asyncfabric.api.EnvType;
import net.asyncfabric.loader.impl.game.LibClassifier.LibraryType;
import net.asyncfabric.loader.impl.util.AsyncExecutor;
import net.asyncfabric.loader.impl.util.LoaderUtil;
import net.asyncfabric.loader.impl.util.ManifestUtil;
import net.asyncfabric.loader.impl.util.SystemProperties;
import net.asyncfabric.loader.impl.util.UrlUtil;
import net.asyncfabric.loader.impl.util.log.Log;
import net.asyncfabric.loader.impl.util.log.LogCategory;

public final class LibClassifier<L extends Enum<L> & LibraryType> {
	private static final boolean DEBUG = SystemProperties.isSet(SystemProperties.DEBUG_LOG_LIB_CLASSIFICATION);

	private final List<L> libs;
	private final Map<L, Path> origins;
	private final Map<L, String> localPaths;
	private final Set<Path> systemLibraries = new HashSet<>();
	private final List<Path> unmatchedOrigins = new ArrayList<>();

	public LibClassifier(Class<L> cls, EnvType env, GameProvider gameProvider) throws IOException {
		L[] libs = cls.getEnumConstants();

		this.libs = new ArrayList<>(libs.length);
		this.origins = new EnumMap<>(cls);
		this.localPaths = new EnumMap<>(cls);

		// game provider libs

		for (L lib : libs) {
			if (lib.isApplicable(env)) {
				this.libs.add(lib);
			}
		}

		// system libs configured through system property

		StringBuilder sb = DEBUG ? new StringBuilder() : null;
		List<Path> systemLibs = GameProviderHelper.getLibraries(SystemProperties.SYSTEM_LIBRARIES);

		if (systemLibs != null) {
			for (Path lib : systemLibs) {
				assert lib.equals(LoaderUtil.normalizeExistingPath(lib));

				if (systemLibraries.add(lib)) {
					if (DEBUG) sb.append(String.format("🇸 %s%n", lib));
				}
			}
		}

		// loader libs

		boolean junitRun = SystemProperties.isSet(SystemProperties.UNIT_TEST);

		for (LoaderLibrary lib : LoaderLibrary.values()) {
			if (!lib.isApplicable(env, junitRun)) continue;

			if (lib.path != null) {
				Path path = LoaderUtil.normalizeExistingPath(lib.path);
				systemLibraries.add(path);

				if (DEBUG) sb.append(String.format("✅ %s %s%n", lib.name(), path));
			} else {
				if (DEBUG) sb.append(String.format("❎ %s%n", lib.name()));
			}
		}

		// game provider itself

		Path gameProviderPath = UrlUtil.getCodeSource(gameProvider.getClass());

		if (gameProviderPath != null) {
			gameProviderPath = LoaderUtil.normalizeExistingPath(gameProviderPath);

			if (systemLibraries.add(gameProviderPath)) {
				if (DEBUG) sb.append(String.format("✅ gameprovider %s%n", gameProviderPath));
			}
		} else {
			if (DEBUG) sb.append("❎ gameprovider");
		}

		if (DEBUG) Log.info(LogCategory.LIB_CLASSIFICATION, "Loader/system libraries:%n%s", sb);

		// game libraries

		List<Path> gameLibs = GameProviderHelper.getLibraries(SystemProperties.GAME_LIBRARIES);

		if (gameLibs != null) {
			process(gameLibs);
		}

		// process indirectly referenced libs

		processManifestClassPath(LoaderLibrary.SERVER_LAUNCH, env, junitRun); // not used by asyncfabric itself, but others add Log4J this way
	}

	private void processManifestClassPath(LoaderLibrary lib, EnvType env, boolean junitRun) throws IOException {
		if (lib.path == null || !lib.isApplicable(env, junitRun) || !Files.isRegularFile(lib.path)) return;

		Manifest manifest;

		try (ZipFile zf = new ZipFile(lib.path.toFile())) {
			ZipEntry entry = zf.getEntry(JarFile.MANIFEST_NAME);
			if (entry == null) return;

			manifest = new Manifest(zf.getInputStream(entry));
		}

		List<URL> cp = ManifestUtil.getClassPath(manifest, lib.path);
		if (cp == null) return;

		for (URL url : cp) {
			process(url);
		}
	}

	public void process(URL url) throws IOException {
		process(UrlUtil.asPath(url));
	}

	@SafeVarargs
	public final void process(Iterable<Path> paths, L... excludedLibs) throws IOException {
		Set<L> excluded = makeSet(excludedLibs);

		for (Path path : paths) {
			process(path, excluded);
		}
	}

	/**
	 * Scans many jars in parallel, then merges the results on the calling thread.
	 *
	 * <p>Walking the whole class path is the slowest part of locating the game: every jar is
	 * opened and probed. Scanning is read-only, so it parallelises cleanly — each worker
	 * reports the libraries it found and the merge applies them in class path order, which
	 * keeps "first match wins" identical to the sequential version.
	 *
	 * @param paths the class path entries to scan
	 * @param excludedLibs libraries to skip
	 */
	@SafeVarargs
	public final void processParallel(List<Path> paths, L... excludedLibs) throws IOException {
		Set<L> excluded = makeSet(excludedLibs);

		if (paths.size() < 8) { // not worth the scheduling overhead
			for (Path path : paths) {
				process(path, excluded);
			}

			return;
		}

		// phase 1: scan on workers, touching no shared state
		ForkJoinPool pool = AsyncExecutor.sharedPool();
		List<CompletableFuture<List<ScanHit<L>>>> pending = new ArrayList<>(paths.size());

		for (Path path : paths) {
			pending.add(CompletableFuture.supplyAsync(() -> {
				try {
					return scan(path, excluded);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}, pool));
		}

		// phase 2: apply in order, exactly like the sequential loop
		for (int i = 0; i < paths.size(); i++) {
			Path path = paths.get(i);
			List<ScanHit<L>> hits;

			try {
				hits = pending.get(i).join();
			} catch (CompletionException e) {
				Throwable cause = e.getCause();

				if (cause instanceof UncheckedIOException) {
					throw ((UncheckedIOException) cause).getCause();
				}

				if (cause instanceof IOException) throw (IOException) cause;

				throw new IOException("error reading " + path, cause);
			}

			// null means the entry is a loader/system library: process() returns early for
			// those and must not report them as unmatched
			if (hits == null) continue;

			boolean matched = false;

			for (ScanHit<L> hit : hits) {
				if (!origins.containsKey(hit.lib)) {
					addLibrary(hit.lib, path, hit.localPath);
					matched = true;
				}
			}

			// a jar whose libraries were all claimed by an earlier entry counts as unmatched,
			// matching the sequential "matched" flag
			if (!matched) {
				// process() records the normalized path, so normalize here too
				unmatchedOrigins.add(LoaderUtil.normalizeExistingPath(path));

				if (DEBUG) Log.info(LogCategory.LIB_CLASSIFICATION, "unmatched %s", path);
			}
		}
	}

	/** A library found inside one class path entry. */
	private static final class ScanHit<L> {
		final L lib;
		final String localPath;

		ScanHit(L lib, String localPath) {
			this.lib = lib;
			this.localPath = localPath;
		}
	}

	/**
	 * Probes one class path entry for known libraries. Pure: reads the file and returns what
	 * it found, mutating nothing.
	 *
	 * @return the hits, or {@code null} if the path is a loader/system library that
	 *         {@code process} would skip entirely
	 */
	private List<ScanHit<L>> scan(Path rawPath, Set<L> excludedLibs) throws IOException {
		Path path = LoaderUtil.normalizeExistingPath(rawPath);

		if (systemLibraries.contains(path)) {
			return null;
		}

		List<ScanHit<L>> hits = new ArrayList<>(2);

		if (Files.isDirectory(path)) {
			for (L lib : libs) {
				if (excludedLibs.contains(lib)) continue;

				for (String p : lib.getPaths()) {
					if (Files.exists(path.resolve(p))) {
						hits.add(new ScanHit<L>(lib, p));
						break;
					}
				}
			}
		} else {
			try (ZipFile zf = new ZipFile(path.toFile())) {
				for (L lib : libs) {
					if (excludedLibs.contains(lib)) continue;

					for (String p : lib.getPaths()) {
						if (zf.getEntry(p) != null) {
							hits.add(new ScanHit<L>(lib, p));
							break;
						}
					}
				}
			} catch (ZipError | IOException e) {
				throw new IOException("error reading " + path, e);
			}
		}

		return hits;
	}

	@SafeVarargs
	public final void process(Path path, L... excludedLibs) throws IOException {
		process(path, makeSet(excludedLibs));
	}

	private static <L extends Enum<L>> Set<L> makeSet(L[] libs) {
		if (libs.length == 0) return Collections.emptySet();

		Set<L> ret = EnumSet.of(libs[0]);

		for (int i = 1; i < libs.length; i++) {
			ret.add(libs[i]);
		}

		return ret;
	}

	private void process(Path path, Set<L> excludedLibs) throws IOException {
		path = LoaderUtil.normalizeExistingPath(path);
		if (systemLibraries.contains(path)) return;

		boolean matched = false;

		if (Files.isDirectory(path)) {
			for (L lib : libs) {
				if (excludedLibs.contains(lib) || origins.containsKey(lib)) continue;

				for (String p : lib.getPaths()) {
					if (Files.exists(path.resolve(p))) {
						matched = true;
						addLibrary(lib, path, p);
						break;
					}
				}
			}
		} else {
			try (ZipFile zf = new ZipFile(path.toFile())) {
				for (L lib : libs) {
					if (excludedLibs.contains(lib) || origins.containsKey(lib)) continue;

					for (String p : lib.getPaths()) {
						if (zf.getEntry(p) != null) {
							matched = true;
							addLibrary(lib, path, p);
							break;
						}
					}
				}
			} catch (ZipError | IOException e) {
				throw new IOException("error reading "+path, e);
			}
		}

		if (!matched) {
			unmatchedOrigins.add(path);

			if (DEBUG) Log.info(LogCategory.LIB_CLASSIFICATION, "unmatched %s", path);
		}
	}

	private void addLibrary(L lib, Path originPath, String localPath) {
		Path prev = origins.put(lib, originPath);
		if (prev != null) throw new IllegalStateException("lib "+lib+" was already added");
		localPaths.put(lib, localPath);

		if (DEBUG) Log.info(LogCategory.LIB_CLASSIFICATION, "%s %s (%s)", lib.name(), originPath, localPath);
	}

	@SafeVarargs
	public final boolean is(Path path, L... libs) {
		for (L lib : libs) {
			if (path.equals(origins.get(lib))) return true;
		}

		return false;
	}

	public boolean has(L lib) {
		return origins.containsKey(lib);
	}

	public Path getOrigin(L lib) {
		return origins.get(lib);
	}

	public String getLocalPath(L lib) {
		return localPaths.get(lib);
	}

	public String getClassName(L lib) {
		String localPath = localPaths.get(lib);
		if (localPath == null || !localPath.endsWith(".class")) return null;

		return localPath.substring(0, localPath.length() - 6).replace('/', '.');
	}

	public List<Path> getUnmatchedOrigins() {
		return unmatchedOrigins;
	}

	/**
	 * Returns system level libraries, typically Loader and its dependencies.
	 */
	public Collection<Path> getSystemLibraries() {
		return systemLibraries;
	}

	public boolean remove(Path path) {
		if (unmatchedOrigins.remove(path)) return true;

		boolean ret = false;

		for (Iterator<Map.Entry<L, Path>> it = origins.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<L, Path> entry = it.next();

			if (entry.getValue().equals(path)) {
				localPaths.remove(entry.getKey());
				it.remove();

				ret = true;
			}
		}

		return ret;
	}

	public interface LibraryType {
		boolean isApplicable(EnvType env);
		String[] getPaths();
	}
}
