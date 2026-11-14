/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
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

package net.asyncfabric.api.event;

/**
 * Where the listeners of an event run.
 *
 * <p>AsyncFabric runs event listeners on worker threads by default: mods are written for this
 * platform and are expected to be thread-safe, so no annotation is needed to get parallelism.
 * The default is {@link #AUTO}.
 *
 * <p>Some events can only be handled on a specific thread — anything that touches the render
 * pipeline, the OpenGL context or per-frame client state must run on the client thread, and
 * world mutation must run on the server thread. Declare those with
 * {@link EventDispatch @EventDispatch(EventThreadMode.MAIN_THREAD)} on the listener interface.
 *
 * @see EventDispatch
 */
public enum EventThreadMode {
	/**
	 * Listeners run in parallel on the dispatch pool, and the emitting thread waits for all of
	 * them before continuing.
	 *
	 * <p>This is the default. Use it for any listener that does not touch game state directly;
	 * listeners that need the game thread hand work over with
	 * {@code net.asyncfabric.api.async.v1.GameThread}.
	 */
	AUTO,

	/**
	 * Listeners run on the emitting thread, one after another, exactly like vanilla Fabric.
	 *
	 * <p>Required for events whose listeners cannot leave the game thread: rendering, window and
	 * input handling, anything reading OpenGL state, and anything mutating the world or registry
	 * without an explicit hand-off.
	 */
	MAIN_THREAD
}
