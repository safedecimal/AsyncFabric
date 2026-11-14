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

package net.asyncfabric.loader.impl.gui;

import java.awt.GraphicsEnvironment;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import net.asyncfabric.loader.impl.AsyncFabricLoaderImpl;
import net.asyncfabric.loader.impl.discovery.WrongModLoaderInfo;
import net.asyncfabric.loader.impl.game.GameProvider;
import net.asyncfabric.loader.impl.gui.AsyncFabricStatusTree.AsyncFabricBasicButtonType;
import net.asyncfabric.loader.impl.gui.AsyncFabricStatusTree.AsyncFabricStatusNode;
import net.asyncfabric.loader.impl.gui.AsyncFabricStatusTree.AsyncFabricStatusTab;
import net.asyncfabric.loader.impl.gui.AsyncFabricStatusTree.AsyncFabricTreeWarningLevel;
import net.asyncfabric.loader.impl.metadata.DependencyOverrides;
import net.asyncfabric.loader.impl.util.LoaderUtil;
import net.asyncfabric.loader.impl.util.Localization;
import net.asyncfabric.loader.impl.util.SystemProperties;
import net.asyncfabric.loader.impl.util.UrlUtil;
import net.asyncfabric.loader.impl.util.log.Log;
import net.asyncfabric.loader.impl.util.log.LogCategory;

/** The main entry point for all asyncfabric-based stuff. */
public final class AsyncFabricGuiEntry {
	/** Opens the given {@link AsyncFabricStatusTree} in a new swing window.
	 *
	 * @throws Exception if something went wrong while opening the window. */
	public static void open(AsyncFabricStatusTree tree) throws Exception {
		GameProvider provider = AsyncFabricLoaderImpl.INSTANCE.tryGetGameProvider();

		if (provider == null && LoaderUtil.hasAwtSupport()
				|| provider != null && provider.hasAwtSupport()) {
			AsyncFabricMainWindow.open(tree, true);
		} else {
			openForked(tree);
		}
	}

	private static void openForked(AsyncFabricStatusTree tree) throws IOException, InterruptedException {
		Path javaBinDir = LoaderUtil.normalizePath(Paths.get(System.getProperty("java.home"), "bin"));
		String[] executables = { "javaw.exe", "java.exe", "java" };
		Path javaPath = null;

		for (String executable : executables) {
			Path path = javaBinDir.resolve(executable);

			if (Files.isRegularFile(path)) {
				javaPath = path;
				break;
			}
		}

		if (javaPath == null) throw new RuntimeException("can't find java executable in "+javaBinDir);

		Process process = new ProcessBuilder(javaPath.toString(), "-Xmx100M", "-cp", UrlUtil.LOADER_CODE_SOURCE.toString(), AsyncFabricGuiEntry.class.getName())
				.redirectOutput(ProcessBuilder.Redirect.INHERIT)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start();

		final Thread shutdownHook = new Thread(process::destroy);

		Runtime.getRuntime().addShutdownHook(shutdownHook);

		try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
			tree.writeTo(os);
		}

		int rVal = process.waitFor();

		Runtime.getRuntime().removeShutdownHook(shutdownHook);

		if (rVal != 0) throw new IOException("subprocess exited with code "+rVal);
	}

	public static void main(String[] args) throws Exception {
		AsyncFabricStatusTree tree = new AsyncFabricStatusTree(new DataInputStream(System.in));
		AsyncFabricMainWindow.open(tree, true);
		System.exit(0);
	}

	/** @param exitAfter If true then this will call {@link System#exit(int)} after showing the gui, otherwise this will
	 *            return normally. */
	public static void displayCriticalError(Throwable exception, boolean exitAfter) {
		Log.error(LogCategory.GENERAL, "A critical error occurred", exception);

		displayError(Localization.format("gui.error.header"), exception, exitAfter);
	}

	public static void displayError(String mainText, Throwable exception, boolean exitAfter) {
		displayError(mainText, exception, tree -> {
			StringWriter error = new StringWriter();
			error.append(mainText);

			if (exception != null) {
				error.append(System.lineSeparator());
				exception.printStackTrace(new PrintWriter(error));
			}

			tree.addButton(Localization.format("gui.button.copyError"), AsyncFabricBasicButtonType.CLICK_MANY).withClipboard(error.toString());
		}, exitAfter);
	}

	public static void displayError(String mainText, Throwable exception, Consumer<AsyncFabricStatusTree> treeCustomiser, boolean exitAfter) {
		displayError(mainText, null, exception, treeCustomiser, exitAfter);
	}

	/**
	 * @param subText secondary line below {@code mainText}, may be {@code null}
	 */
	public static void displayError(String mainText, String subText, Throwable exception, boolean exitAfter) {
		displayError(mainText, subText, exception, tree -> {
			StringWriter error = new StringWriter();
			error.append(mainText);

			if (subText != null) {
				error.append(System.lineSeparator()).append(subText);
			}

			if (exception != null) {
				error.append(System.lineSeparator());
				exception.printStackTrace(new PrintWriter(error));
			}

			tree.addButton(Localization.format("gui.button.copyError"), AsyncFabricBasicButtonType.CLICK_MANY).withClipboard(error.toString());
		}, exitAfter);
	}

	public static void displayError(String mainText, String subText, Throwable exception, Consumer<AsyncFabricStatusTree> treeCustomiser, boolean exitAfter) {
		boolean isCI = System.getenv("CI") != null;
		boolean isNoGui = SystemProperties.isSet(SystemProperties.NO_GUI);

		GameProvider provider = AsyncFabricLoaderImpl.INSTANCE.tryGetGameProvider();

		if (!isCI && !isNoGui && !GraphicsEnvironment.isHeadless() && (provider == null || provider.canOpenErrorGui())) {
			String title = "AsyncFabric Loader " + AsyncFabricLoaderImpl.VERSION;
			AsyncFabricStatusTree tree = new AsyncFabricStatusTree(title, mainText);
			tree.subText = subText;
			AsyncFabricStatusTab crashTab = tree.addTab(Localization.format("gui.tab.crash"));

			if (exception != null) {
				crashTab.node.addCleanedException(exception);
			} else {
				crashTab.node.addMessage(Localization.format("gui.error.missingException"), AsyncFabricTreeWarningLevel.NONE);
			}

			addWrongModLoaderDetails(crashTab.node);

			DependencyOverrides dependencyOverrides = AsyncFabricLoaderImpl.INSTANCE.getDependencyOverrides();

			if (dependencyOverrides != null && !dependencyOverrides.getAffectedModIds().isEmpty()) {
				crashTab.node.addMessage(Localization.format("gui.error.dependencyOverrides",
						String.join(", ", dependencyOverrides.getAffectedModIds())), AsyncFabricTreeWarningLevel.WARN);
			}

			// Maybe add an "open mods folder" button?
			// or should that be part of the main tree's right-click menu?
			tree.addButton(Localization.format("gui.button.exit"), AsyncFabricBasicButtonType.CLICK_ONCE).makeClose();
			treeCustomiser.accept(tree);

			try {
				open(tree);
			} catch (Exception e) {
				if (exitAfter) {
					Log.warn(LogCategory.GENERAL, "Failed to open the error gui!", e);
				} else {
					throw new RuntimeException("Failed to open the error gui!", e);
				}
			}
		}

		if (exitAfter) {
			System.exit(1);
		}
	}

	/**
	 * Adds the wrong-mod-loader sections to the crash tab:
	 * mods rejected at startup, and loaded mods that also carry foreign metadata.
	 */
	private static void addWrongModLoaderDetails(AsyncFabricStatusNode root) {
		List<WrongModLoaderInfo> rejected = AsyncFabricLoaderImpl.INSTANCE != null
				? AsyncFabricLoaderImpl.INSTANCE.getWrongModLoaderMods() : Collections.emptyList();
		List<WrongModLoaderInfo> foreignMetadata = AsyncFabricLoaderImpl.INSTANCE != null
				? AsyncFabricLoaderImpl.INSTANCE.getForeignMetadataMods() : Collections.emptyList();

		if (!rejected.isEmpty()) {
			AsyncFabricStatusNode node = root.addMessage("Regular Fabric / other mod loader mods (not loaded):", AsyncFabricTreeWarningLevel.ERROR);

			for (WrongModLoaderInfo info : rejected) {
				node.addMessage(info.getModFileName() + "  [" + info.getLoaderName() + "]", AsyncFabricTreeWarningLevel.ERROR);
			}
		}

		if (!foreignMetadata.isEmpty()) {
			AsyncFabricStatusNode node = root.addMessage("Mods with foreign loader metadata (loaded anyway):", AsyncFabricTreeWarningLevel.WARN);

			for (WrongModLoaderInfo info : foreignMetadata) {
				node.addMessage(info.getModFileName() + "  [" + info.getLoaderName() + "] — the crash may be caused by this", AsyncFabricTreeWarningLevel.WARN);
			}
		}
	}
}
