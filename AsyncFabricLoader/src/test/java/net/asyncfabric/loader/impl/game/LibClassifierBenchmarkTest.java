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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import net.asyncfabric.api.EnvType;

/**
 * Not a correctness test: measures the wall time of scanning the real class path both ways
 * and prints it, so the parallel version can be checked against the sequential one on the
 * machine it ships on. Runs as part of the normal suite and always passes; the numbers are in
 * the test output.
 *
 * <p>Disabled unless {@code asyncfabric.loader.benchmark} is set, since timings are
 * meaningless on a loaded CI box and the scan is I/O bound.
 */
class LibClassifierBenchmarkTest {
	@Test
	void benchmarkRealClassPath() throws IOException {
		if (!Boolean.getBoolean("asyncfabric.loader.benchmark")) return;

		List<Path> classPath = new ArrayList<>();

		for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
			if (entry.isEmpty()) continue;

			classPath.add(Paths.get(entry));
		}

		if (classPath.size() < 8) return;

		// warm up so the first run does not pay for class loading and path normalization
		run(classPath, false);
		run(classPath, true);

		long sequential = run(classPath, false);
		long parallel = run(classPath, true);

		System.out.printf(Locale.ROOT, "%nLibClassifier: %d class path entries%n", classPath.size());
		System.out.printf(Locale.ROOT, "  sequential: %6.2f ms%n", sequential / 1e6);
		System.out.printf(Locale.ROOT, "  parallel:   %6.2f ms%n", parallel / 1e6);
		System.out.printf(Locale.ROOT, "  speedup:    %6.2fx%n", (double) sequential / parallel);
	}

	private long run(List<Path> classPath, boolean parallel) throws IOException {
		LibClassifier<TestLibrary> classifier = new LibClassifier<>(TestLibrary.class, EnvType.CLIENT, mock(GameProvider.class));
		long start = System.nanoTime();

		if (parallel) {
			classifier.processParallel(classPath);
		} else {
			classifier.process(classPath);
		}

		long elapsed = System.nanoTime() - start;
		assertNotNull(classifier.getUnmatchedOrigins());

		return elapsed;
	}

	private enum TestLibrary implements LibClassifier.LibraryType {
		TEST_CLIENT(new String[] { "test/game/client/Main.class" }),
		TEST_SERVER(new String[] { "test/game/server/Server.class" });

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
