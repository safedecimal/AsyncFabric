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

package net.asyncfabric.loader.impl.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inspects a jar or an extracted mod directory and reports which mod loader its
 * metadata belongs to.
 */
final class WrongModLoaderDetector {
	private WrongModLoaderDetector() { }

	/**
	 * Classifies a path that was not loaded as an AsyncFabric mod.
	 *
	 * @return loader info when the path carries another loader's metadata,
	 *         or {@code null} when it is a plain library jar.
	 */
	static WrongModLoaderInfo inspectUnloaded(Path path) {
		try {
			List<String> entries = Files.isDirectory(path) ? listDir(path) : listJar(path);

			if (entries == null) {
				return null;
			}

			boolean regularFabric = entries.contains(DiscoveredManifests.FABRIC);
			String otherLoader = otherLoaderOf(entries);

			if (regularFabric) {
				return new WrongModLoaderInfo(fileName(path), "Fabric", WrongModLoaderInfo.Kind.REGULAR_FABRIC);
			}

			if (otherLoader != null) {
				return new WrongModLoaderInfo(fileName(path), otherLoader, WrongModLoaderInfo.Kind.OTHER_LOADER);
			}

			return null;
		} catch (IOException e) {
			return null; // unreadable is not a loader problem
		}
	}

	/**
	 * Checks a mod that does have an AsyncFabric manifest for foreign loader
	 * metadata. Such a mod still loads, but is reported if it later crashes.
	 *
	 * @return loader info for the foreign metadata, or {@code null}.
	 */
	static WrongModLoaderInfo inspectLoaded(Path path) {
		try {
			List<String> entries = Files.isDirectory(path) ? listDir(path) : listJar(path);

			if (entries == null) {
				return null;
			}

			if (entries.contains(DiscoveredManifests.FABRIC)) {
				return new WrongModLoaderInfo(fileName(path), "Fabric", WrongModLoaderInfo.Kind.REGULAR_FABRIC);
			}

			String otherLoader = otherLoaderOf(entries);

			if (otherLoader != null) {
				return new WrongModLoaderInfo(fileName(path), otherLoader, WrongModLoaderInfo.Kind.OTHER_LOADER);
			}

			return null;
		} catch (IOException e) {
			return null;
		}
	}

	/** @return entry names, or {@code null} if the jar cannot be opened. */
	private static List<String> listJar(Path path) throws IOException {
		try (ZipFile zf = new ZipFile(path.toFile())) {
			List<String> ret = new ArrayList<>();
			Enumeration<? extends ZipEntry> entries = zf.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (!entry.isDirectory()) {
					ret.add(normalize(entry.getName()));
				}
			}

			return ret;
		}
	}

	private static List<String> listDir(Path dir) throws IOException {
		final List<String> ret = new ArrayList<>();

		try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
			stream.filter(Files::isRegularFile)
					.forEach(p -> ret.add(normalize(dir.relativize(p).toString())));
		}

		return ret;
	}

	private static String otherLoaderOf(List<String> entries) {
		for (String entry : entries) {
			if (entry.equals(DiscoveredManifests.NEOFORGE)) {
				return "NeoForge";
			}

			if (entry.equals(DiscoveredManifests.MODS_TOML) || entry.startsWith("meta-inf/forge/")) {
				return "Forge";
			}

			if (entry.equals(DiscoveredManifests.QUILT)) {
				return "Quilt";
			}

			if (entry.equals(DiscoveredManifests.RIFT)) {
				return "Rift";
			}

			if (entry.equals(DiscoveredManifests.LITELOADER)) {
				return "LiteLoader";
			}
		}

		return null;
	}

	private static String normalize(String entryName) {
		return entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private static String fileName(Path path) {
		Path name = path.getFileName();

		return name != null ? name.toString() : path.toString();
	}
}
