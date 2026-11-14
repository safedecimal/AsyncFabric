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

package net.asyncfabric.api.async.v1;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.Identifier;

/**
 * Asynchronous counterpart of {@link net.asyncfabric.api.event.Event}.
 *
 * <p>Listeners of an {@code AsyncEvent} return a {@link java.util.concurrent.CompletableFuture}
 * instead of a plain value, so the emitter does not have to block while they run. The
 * future completes when the event has been processed by every listener.
 *
 * <p>Two dispatch strategies exist, chosen when the event is created:
 * <ul>
 * <li>{@link AsyncEventFactory#createOrdered} runs listeners one at a time, in phase order,
 *     passing each listener's result to the next one. Use it for events whose semantics
 *     depend on ordering, such as cancellable events or value transformations.</li>
 * <li>{@link AsyncEventFactory#createParallel} runs all listeners at once on the
 *     {@linkplain AsyncScheduler scheduler} and reduces their results. Use it for
 *     independent listeners that do not observe each other.</li>
 * </ul>
 *
 * <p>Listeners are always invoked off the game thread. If a listener needs the game thread,
 * it must hand work over with {@link GameThread}.
 *
 * <p>An exception thrown by a listener does not prevent the remaining listeners from running;
 * it completes the returned future exceptionally instead.
 *
 * @param <T> the listener type, whose single abstract method returns a {@code CompletableFuture}
 * @see AsyncEventFactory
 */
@ApiStatus.NonExtendable
public abstract class AsyncEvent<T> {
	/**
	 * The invoker field. This should be updated by the implementation to
	 * always refer to an instance containing all code that should be
	 * executed upon event emission.
	 */
	protected volatile T invoker;

	/**
	 * Returns the invoker instance.
	 *
	 * <p>An "invoker" is an object which hides multiple registered
	 * listeners of type T under one instance of type T, executing
	 * them and combining their results.
	 *
	 * @return The invoker instance.
	 */
	public final T invoker() {
		return invoker;
	}

	/**
	 * Register a listener to the event, in the default phase.
	 * Have a look at {@link #addPhaseOrdering} for an explanation of event phases.
	 *
	 * @param listener The desired listener.
	 */
	public abstract void register(T listener);

	/**
	 * The identifier of the default phase.
	 */
	public static final Identifier DEFAULT_PHASE = Identifier.fromNamespaceAndPath("fabric", "default");

	/**
	 * Register a listener to the event for the specified phase.
	 *
	 * @param phase Identifier of the phase this listener should be registered for. It will be created if it didn't exist yet.
	 * @param listener The desired listener.
	 */
	public void register(Identifier phase, T listener) {
		// This is done to keep compatibility with existing AsyncEvent subclasses, but they should really not be subclassing AsyncEvent.
		register(listener);
	}

	/**
	 * Request that listeners registered for one phase be executed before listeners registered for another phase.
	 *
	 * <p>Incompatible ordering constraints such as cycles will lead to inconsistent behavior:
	 * some constraints will be respected and some will be ignored. If this happens, a warning will be logged.
	 *
	 * @param firstPhase The identifier of the phase that should run before the other. It will be created if it didn't exist yet.
	 * @param secondPhase The identifier of the phase that should run after the other. It will be created if it didn't exist yet.
	 */
	public void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
		// This is not abstract to avoid breaking existing AsyncEvent subclasses, but they should really not be subclassing AsyncEvent.
	}
}
