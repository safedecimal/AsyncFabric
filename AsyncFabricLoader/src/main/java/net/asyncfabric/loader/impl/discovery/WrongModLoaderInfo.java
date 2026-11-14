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

import java.util.Objects;

/**
 * A mod that was found on the class path or in the mods folder but is not an
 * AsyncFabric mod: it either ships a regular Fabric manifest (fabric.mod.json)
 * without an asyncfabric.mod.json, or it ships another loader's metadata
 * entirely (Forge, NeoForge, Quilt, ...).
 */
public final class WrongModLoaderInfo {
	public enum Kind {
		/** Regular Fabric mod: has fabric.mod.json, but no asyncfabric.mod.json. */
		REGULAR_FABRIC,
		/** Another loader's mod: Forge/NeoForge/Quilt/... metadata, no asyncfabric.mod.json. */
		OTHER_LOADER,
	}

	private final String modFileName;
	private final String loaderName;
	private final Kind kind;

	public WrongModLoaderInfo(String modFileName, String loaderName, Kind kind) {
		this.modFileName = Objects.requireNonNull(modFileName, "modFileName");
		this.loaderName = Objects.requireNonNull(loaderName, "loaderName");
		this.kind = Objects.requireNonNull(kind, "kind");
	}

	/** File name (or directory name) of the offending mod, e.g. {@code sodium-0.5.jar}. */
	public String getModFileName() {
		return modFileName;
	}

	/** Human readable loader name, e.g. {@code Fabric}, {@code Forge} or {@code NeoForge}. */
	public String getLoaderName() {
		return loaderName;
	}

	public Kind getKind() {
		return kind;
	}

	@Override
	public String toString() {
		return modFileName + " (" + loaderName + ")";
	}
}
