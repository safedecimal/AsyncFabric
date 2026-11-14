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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import net.asyncfabric.api.EnvType;
import net.asyncfabric.loader.impl.AsyncFabricLoaderImpl;
import net.asyncfabric.loader.impl.game.GameProvider;
import net.asyncfabric.loader.impl.launch.AsyncFabricLauncher;
import net.asyncfabric.loader.impl.launch.AsyncFabricLauncherBase;
import net.asyncfabric.loader.impl.util.LoaderUtil;

public class GetNonAsyncFabricModsTest {
	private AsyncFabricLoaderImpl loader;
	private ModDiscoverer discoverer;
	private MockedConstruction<AsyncFabricLoaderImpl> loaderConstruction;

	/*
	 * Set up the mock loader and discoverer
	 */
	@BeforeEach
	public void setUp() {
		GameProvider provider = mock();
		when(provider.getBuiltinMods()).thenReturn(Collections.emptyList());

		AsyncFabricLauncher launcher = mock();
		when(launcher.getEnvironmentType()).thenReturn(EnvType.CLIENT);
		when(launcher.isDevelopment()).thenReturn(false);
		AsyncFabricLauncherBase.setLauncher(launcher);
		loader = mock();
		when(loader.getGameProvider()).thenReturn(provider);
		when(loader.isDevelopmentEnvironment()).thenReturn(false);

		loaderConstruction = Mockito.mockConstructionWithAnswer(AsyncFabricLoaderImpl.class, invocation -> loader);

		discoverer = new ModDiscoverer(mock(), mock());
		// the fixtures act as mods the player installed
		discoverer.setUserModsDirectory(Paths.get("src/test/resources/testing/discovery"));
		discoverer.addCandidateFinder(new MockCandidateFinder());
	}

	@AfterEach
	public void tearDown() {
		loaderConstruction.close();
	}

	/*
	 * Test that the discoverer can find non-asyncfabric mods
	 */
	@Test
	public void testGetNonAsyncFabricMods() throws ModResolutionException {
		List<ModCandidateImpl> acceptedMods = discoverer.discoverMods(loader, new HashMap<String, Set<ModCandidateImpl>>());

		boolean foundDummyAsyncFabricMod = false;

		for (ModCandidateImpl acceptedMod : acceptedMods) {
			if (acceptedMod.getId().equals("dummy")) {
				foundDummyAsyncFabricMod = true;
				break;
			}
		}

		Assertions.assertTrue(foundDummyAsyncFabricMod);

		List<Path> nonAsyncFabricMods = discoverer.getNonAsyncFabricMods();

		// the plain library jar and the regular Fabric / NeoForge / Forge mods are all not AsyncFabric mods
		Assertions.assertEquals(4, nonAsyncFabricMods.size());
		Assertions.assertTrue(nonAsyncFabricMods.contains(fixture("dummyNonFabricMod.jar")));
		Assertions.assertTrue(nonAsyncFabricMods.contains(fixture("dummyRegularFabricMod.jar")));
		Assertions.assertTrue(nonAsyncFabricMods.contains(fixture("dummyNeoForgeMod.jar")));
		Assertions.assertTrue(nonAsyncFabricMods.contains(fixture("dummyForgeMod.jar")));

		// regular Fabric mods and foreign loader mods must be reported as wrong
		// mod loader, while a plain library jar must not
		List<WrongModLoaderInfo> wrong = discoverer.getWrongModLoaderMods();

		Assertions.assertEquals(3, wrong.size());
		Assertions.assertFalse(wrong.stream().anyMatch(info -> info.getModFileName().equals("dummyNonFabricMod.jar")));

		WrongModLoaderInfo fabric = find(wrong, "dummyRegularFabricMod.jar");
		Assertions.assertEquals(WrongModLoaderInfo.Kind.REGULAR_FABRIC, fabric.getKind());
		Assertions.assertEquals("Fabric", fabric.getLoaderName());

		WrongModLoaderInfo neo = find(wrong, "dummyNeoForgeMod.jar");
		Assertions.assertEquals(WrongModLoaderInfo.Kind.OTHER_LOADER, neo.getKind());
		Assertions.assertEquals("NeoForge", neo.getLoaderName());

		WrongModLoaderInfo forge = find(wrong, "dummyForgeMod.jar");
		Assertions.assertEquals(WrongModLoaderInfo.Kind.OTHER_LOADER, forge.getKind());
		Assertions.assertEquals("Forge", forge.getLoaderName());
	}

	private static WrongModLoaderInfo find(List<WrongModLoaderInfo> infos, String fileName) {
		return infos.stream()
				.filter(info -> info.getModFileName().equals(fileName))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no wrong-loader entry for " + fileName));
	}

	private static Path fixture(String name) {
		return Paths.get("src/test/resources/testing/discovery/" + name).toAbsolutePath();
	}

	/*
	 * Mock candidate finder that returns one AsyncFabric mod, one plain library
	 * jar and mods of other loaders
	 */
	public static class MockCandidateFinder implements ModCandidateFinder {
		@Override
		public void findCandidates(ModCandidateConsumer out) {
			for (String name : new String[] {
					"dummyFabricMod.jar",
					"dummyNonFabricMod.jar",
					"dummyRegularFabricMod.jar",
					"dummyNeoForgeMod.jar",
					"dummyForgeMod.jar" }) {
				out.accept(LoaderUtil.normalizePath(Paths.get("src/test/resources/testing/discovery/" + name)), false);
			}
		}
	}
}
