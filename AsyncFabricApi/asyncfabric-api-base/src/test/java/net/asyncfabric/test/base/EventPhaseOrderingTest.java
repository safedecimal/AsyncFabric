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

package net.asyncfabric.test.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import net.asyncfabric.api.event.Event;
import net.asyncfabric.api.event.EventFactory;

/**
 * Guards the ordering contract that parallel dispatch must not break.
 *
 * <p>Phase ordering is a documented part of the event API: listeners of an earlier phase run
 * before listeners of a later one. Parallel dispatch may overlap listeners <em>within</em> a
 * phase, but the phase boundaries themselves must stay hard. These tests fail if a future change
 * flattens the phases.
 */
class EventPhaseOrderingTest {
	private static final Identifier FIRST = Identifier.fromNamespaceAndPath("fabric", "first");
	private static final Identifier SECOND = Identifier.fromNamespaceAndPath("fabric", "second");

	@Test
	void phasesRunInDeclaredOrder() {
		Event<Listener> event = EventFactory.createWithPhases(Listener.class, listeners -> () -> {
			for (Listener listener : listeners) {
				listener.onTest();
			}
		}, FIRST, SECOND, Event.DEFAULT_PHASE);

		List<String> order = new CopyOnWriteArrayList<>();

		event.register(Event.DEFAULT_PHASE, () -> order.add("default"));
		event.register(FIRST, () -> order.add("first"));
		event.register(SECOND, () -> order.add("second"));

		event.invoker().onTest();

		assertEquals(List.of("first", "second", "default"), order);
	}

	@Test
	void everyListenerOfAPhaseFinishesBeforeTheNextPhaseStarts() {
		Event<Listener> event = EventFactory.createWithPhases(Listener.class, listeners -> () -> {
			for (Listener listener : listeners) {
				listener.onTest();
			}
		}, FIRST, SECOND, Event.DEFAULT_PHASE);

		AtomicInteger firstPhaseDone = new AtomicInteger();
		AtomicInteger violations = new AtomicInteger();

		for (int i = 0; i < 6; i++) {
			event.register(FIRST, firstPhaseDone::incrementAndGet);
		}

		for (int i = 0; i < 3; i++) {
			event.register(SECOND, () -> {
				if (firstPhaseDone.get() != 6) violations.incrementAndGet();
			});
		}

		event.invoker().onTest();

		assertEquals(0, violations.get(), "a later phase started before an earlier one finished");
		assertEquals(6, firstPhaseDone.get());
	}

	@Test
	void interleavedRegistrationsStillRespectPhases() {
		Event<Listener> event = EventFactory.createWithPhases(Listener.class, listeners -> () -> {
			for (Listener listener : listeners) {
				listener.onTest();
			}
		}, FIRST, SECOND, Event.DEFAULT_PHASE);

		List<String> order = new CopyOnWriteArrayList<>();

		event.register(Event.DEFAULT_PHASE, () -> order.add("default-1"));
		event.register(FIRST, () -> order.add("first-1"));
		event.register(SECOND, () -> order.add("second-1"));
		event.register(SECOND, () -> order.add("second-2"));
		event.register(FIRST, () -> order.add("first-2"));
		event.register(Event.DEFAULT_PHASE, () -> order.add("default-2"));

		event.invoker().onTest();

		// within a phase the order is free, between phases it is not: every "first" listener
		// must run before every "second" listener, and those before every "default" one
		assertEquals(6, order.size());
		assertTrue(maxIndex(order, "first") < minIndex(order, "second"), "second started before first finished");
		assertTrue(maxIndex(order, "second") < minIndex(order, "default"), "default started before second finished");
	}

	private static int minIndex(List<String> order, String prefix) {
		for (int i = 0; i < order.size(); i++) {
			if (order.get(i).startsWith(prefix)) return i;
		}

		return -1;
	}

	private static int maxIndex(List<String> order, String prefix) {
		for (int i = order.size() - 1; i >= 0; i--) {
			if (order.get(i).startsWith(prefix)) return i;
		}

		return -1;
	}

	@Test
	void phaseOrderingAddedLaterIsHonoured() {
		Event<Listener> event = EventFactory.createArrayBacked(Listener.class, listeners -> () -> {
			for (Listener listener : listeners) {
				listener.onTest();
			}
		});

		Identifier early = Identifier.fromNamespaceAndPath("fabric", "early");
		event.addPhaseOrdering(early, Event.DEFAULT_PHASE);

		List<String> order = new CopyOnWriteArrayList<>();

		event.register(Event.DEFAULT_PHASE, () -> order.add("default"));
		event.register(early, () -> order.add("early"));

		event.invoker().onTest();

		assertEquals(List.of("early", "default"), order);
	}

	@Test
	void listenerFailureStillPropagatesThroughPhases() {
		Event<Listener> event = EventFactory.createWithPhases(Listener.class, listeners -> () -> {
			for (Listener listener : listeners) {
				listener.onTest();
			}
		}, FIRST, SECOND, Event.DEFAULT_PHASE);

		event.register(FIRST, () -> {
			throw new IllegalStateException("first phase failure");
		});
		event.register(SECOND, () -> { });

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> event.invoker().onTest());

		assertEquals("first phase failure", thrown.getMessage());
	}

	@FunctionalInterface
	interface Listener {
		void onTest();
	}
}
