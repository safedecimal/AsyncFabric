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

/** Well-known mod metadata entry names used by the loaders we can detect. */
final class DiscoveredManifests {
	/** Our manifest, the only one AsyncFabric loads. */
	static final String ASYNC = "asyncfabric.mod.json";
	/** Regular Fabric manifest, no longer loaded by AsyncFabric. */
	static final String FABRIC = "fabric.mod.json";

	static final String NEOFORGE = "meta-inf/neoforge.mods.toml";
	static final String MODS_TOML = "meta-inf/mods.toml";
	static final String QUILT = "quilt.mod.json";
	static final String RIFT = "riftmod.json";
	static final String LITELOADER = "litemod.json";

	private DiscoveredManifests() { }
}
