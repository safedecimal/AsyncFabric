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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.asyncfabric.api.EnvType;
import net.asyncfabric.loader.impl.util.SystemProperties;

/**
 * Pins {@link LibClassifier#processParallel} to the exact behaviour of the sequential
 * {@link LibClassifier#process(Iterable, Enum[])}, including the cases that are easy to get
 * wrong when the scan is split from the merge:
 *
 * <ul>
 *     <li>a loader/system library must not be reported as unmatched — the sequential version
 *     returns before recording it, and adding it to the unmatched list pushes loader jars onto
 *     the game class path, which breaks Mixin's class loader assumptions;</li>
 *     <li>a jar whose libraries were all claimed by an earlier entry is unmatched;</li>
 *     <li>first match wins in class path order.</li>
 * </ul>
 */
class LibClassifierParallelTest {
	private static final String CLIENT_PATH = "test/game/client/Main.class";
	private static final String SERVER_PATH = "test/game/server/Server.class";

	@TempDir
	Path tempDir;

	private final List<Path> classPath = new ArrayList<>();

	@BeforeEach
	void setUp() {
	}

	@AfterEach
	void tearDown() {
		System.clearProperty(SystemProperties.SYSTEM_LIBRARIES);
		System.clearProperty(SystemProperties.GAME_LIBRARIES);
	}

	@Test
	void parallelMatchesSequential() throws IOException {
		// one jar per library type, then plenty of noise so the parallel path is taken
		classPath.add(createJar("client.jar", CLIENT_PATH));
		classPath.add(createJar("server.jar", SERVER_PATH));

		for (int i = 0; i < 10; i++) {
			classPath.add(createJar("noise-" + i + ".jar", "noise/" + i + ".class"));
		}

		assertSameClassification(classPath);
	}

	@Test
	void duplicateLibraryIsUnmatchedInBoth() throws IOException {
		// both jars carry the client marker: the first claims it, the second is unmatched
		classPath.add(createJar("client-first.jar", CLIENT_PATH));
		classPath.add(createJar("client-second.jar", CLIENT_PATH));

		for (int i = 0; i < 10; i++) {
			classPath.add(createJar("noise-" + i + ".jar", "noise/" + i + ".class"));
		}

		assertSameClassification(classPath);
	}

	@Test
	void systemLibraryIsNotUnmatchedInEither() throws IOException {
		Path systemLib = createJar("system-lib.jar", "test/game/client/Main.class");
		System.setProperty(SystemProperties.SYSTEM_LIBRARIES, systemLib.toString());

		classPath.add(systemLib);
		classPath.add(createJar("server.jar", SERVER_PATH));

		for (int i = 0; i < 10; i++) {
			classPath.add(createJar("noise-" + i + ".jar", "noise/" + i + ".class"));
		}

		// the system library is skipped by both, so it is never an origin nor unmatched
		assertSameClassification(classPath);
		assertEquals(false, newClassifier().getUnmatchedOrigins().contains(systemLib));
	}

	@Test
	void shortClassPathUsesSequentialFallback() throws IOException {
		classPath.add(createJar("client.jar", CLIENT_PATH));
		classPath.add(createJar("noise.jar", "noise/0.class"));

		assertSameClassification(classPath);
	}

	private void assertSameClassification(List<Path> paths) throws IOException {
		LibClassifier<TestLibrary> sequential = newClassifier();
		LibClassifier<TestLibrary> parallel = newClassifier();

		sequential.process(paths);
		parallel.processParallel(paths);

		for (TestLibrary lib : TestLibrary.values()) {
			assertEquals(sequential.getOrigin(lib), parallel.getOrigin(lib), "origin of " + lib);
			assertEquals(sequential.getLocalPath(lib), parallel.getLocalPath(lib), "local path of " + lib);
		}

		assertEquals(sorted(sequential.getUnmatchedOrigins()), sorted(parallel.getUnmatchedOrigins()), "unmatched origins");
	}

	private LibClassifier<TestLibrary> newClassifier() throws IOException {
		return new LibClassifier<>(TestLibrary.class, EnvType.CLIENT, mock(GameProvider.class));
	}

	private static List<String> sorted(List<Path> paths) {
		List<String> names = new ArrayList<>(paths.size());

		for (Path path : paths) {
			names.add(path.getFileName().toString());
		}

		Collections.sort(names);

		return names;
	}

	private Path createJar(String name, String... entries) throws IOException {
		Path jar = tempDir.resolve(name);

		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
			for (String entry : entries) {
				jos.putNextEntry(new ZipEntry(entry));
				jos.write(name.getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}

		return jar;
	}

	private enum TestLibrary implements LibClassifier.LibraryType {
		TEST_CLIENT(new String[] { CLIENT_PATH }),
		TEST_SERVER(new String[] { SERVER_PATH });

		private final String[] paths;

		TestLibrary(String[] paths) {
			this.paths = paths;
		}

		@Override
		public boolean isApplicable(EnvType env) {
			return true;
		}

		@Override
		public String[] getPaths() {
			return paths;
		}
	}
}
