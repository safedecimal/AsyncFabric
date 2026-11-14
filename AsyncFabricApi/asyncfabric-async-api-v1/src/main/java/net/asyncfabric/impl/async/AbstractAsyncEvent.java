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

package net.asyncfabric.impl.async;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import net.minecraft.resources.Identifier;

import net.asyncfabric.api.async.v1.AsyncEvent;
import net.asyncfabric.impl.base.toposort.NodeSorting;

/**
 * Shared phase handling for async events.
 *
 * <p>The registration and phase-order machinery mirrors
 * {@code net.asyncfabric.impl.base.event.ArrayBackedEvent}: the listener array is
 * rebuilt under a lock and the {@code volatile} invoker is swapped as the last step, so
 * an emitting thread always observes a fully built invoker. Phase ordering reuses
 * {@link NodeSorting} unchanged — topological ordering is orthogonal to async dispatch.
 */
abstract class AbstractAsyncEvent<T> extends AsyncEvent<T> {
	private final Function<T[], T> invokerFactory;
	private final Object lock = new Object();
	private final Map<Identifier, AsyncEventPhaseData<T>> phases = new LinkedHashMap<>();
	private final List<AsyncEventPhaseData<T>> sortedPhases = new ArrayList<>();
	T[] handlers;

	@SuppressWarnings("unchecked")
	AbstractAsyncEvent(Class<T> type, Function<T[], T> invokerFactory) {
		this.invokerFactory = invokerFactory;
		this.handlers = (T[]) Array.newInstance(type, 0);
		update();
	}

	private void update() {
		this.invoker = invokerFactory.apply(handlers);
	}

	@Override
	public void register(T listener) {
		register(DEFAULT_PHASE, listener);
	}

	@Override
	public void register(Identifier phaseIdentifier, T listener) {
		Objects.requireNonNull(phaseIdentifier, "Tried to register a listener for a null phase!");
		Objects.requireNonNull(listener, "Tried to register a null listener!");

		synchronized (lock) {
			getOrCreatePhase(phaseIdentifier, true).addListener(listener);
			rebuildInvoker(handlers.length + 1);
		}
	}

	/** Creates an empty phase so it participates in ordering before any listener lands in it. */
	public void addPhase(Identifier id) {
		synchronized (lock) {
			getOrCreatePhase(id, true);
		}
	}

	@Override
	public void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
		Objects.requireNonNull(firstPhase, "Tried to add an ordering for a null phase.");
		Objects.requireNonNull(secondPhase, "Tried to add an ordering for a null phase.");
		if (firstPhase.equals(secondPhase)) throw new IllegalArgumentException("Tried to add a phase that depends on itself.");

		synchronized (lock) {
			AsyncEventPhaseData<T> first = getOrCreatePhase(firstPhase, false);
			AsyncEventPhaseData<T> second = getOrCreatePhase(secondPhase, false);
			AsyncEventPhaseData.link(first, second);
			NodeSorting.sort(sortedPhases, "event phases", Comparator.comparing(data -> data.id));
			rebuildInvoker(handlers.length);
		}
	}

	private AsyncEventPhaseData<T> getOrCreatePhase(Identifier id, boolean sortIfCreate) {
		AsyncEventPhaseData<T> phase = phases.get(id);

		if (phase == null) {
			@SuppressWarnings("unchecked")
			Class<? super T> componentType = (Class<? super T>) handlers.getClass().getComponentType();
			phase = new AsyncEventPhaseData<>(id, componentType);
			phases.put(id, phase);
			sortedPhases.add(phase);

			if (sortIfCreate) {
				NodeSorting.sort(sortedPhases, "event phases", Comparator.comparing(data -> data.id));
			}
		}

		return phase;
	}

	@SuppressWarnings("unchecked")
	private void rebuildInvoker(int newLength) {
		if (sortedPhases.size() == 1) {
			// Special case with a single phase: use the array of the phase directly.
			handlers = sortedPhases.get(0).listeners;
		} else {
			T[] newHandlers = (T[]) Array.newInstance(handlers.getClass().getComponentType(), newLength);
			int idx = 0;

			for (AsyncEventPhaseData<T> phase : sortedPhases) {
				System.arraycopy(phase.listeners, 0, newHandlers, idx, phase.listeners.length);
				idx += phase.listeners.length;
			}

			handlers = newHandlers;
		}

		update();
	}

	static void ensureContainsDefault(Identifier[] defaultPhases) {
		for (Identifier id : defaultPhases) {
			if (id.equals(DEFAULT_PHASE)) {
				return;
			}
		}

		throw new IllegalArgumentException("The event phases must contain AsyncEvent.DEFAULT_PHASE.");
	}

	static void ensureNoDuplicates(Identifier[] defaultPhases) {
		for (int i = 0; i < defaultPhases.length; ++i) {
			for (int j = i + 1; j < defaultPhases.length; ++j) {
				if (defaultPhases[i].equals(defaultPhases[j])) {
					throw new IllegalArgumentException("Duplicate event phase: " + defaultPhases[i]);
				}
			}
		}
	}
}
