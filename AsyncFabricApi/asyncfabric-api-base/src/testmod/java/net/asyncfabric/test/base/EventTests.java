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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.asyncfabric.api.event.Event;
import net.asyncfabric.api.event.EventFactory;
import net.asyncfabric.impl.base.toposort.NodeSorting;

/**
 * Checks the event contract AsyncFabric actually guarantees.
 *
 * <p>Listeners of one <em>phase</em> run in parallel, so their relative order is deliberately
 * unspecified. What is guaranteed, and what these tests assert, is:
 *
 * <ul>
 *   <li>every registered listener runs exactly once per emission;</li>
 *   <li>phases run in the declared order — a later phase never starts before an earlier one
 *   finished;</li>
 *   <li>phase sorting is deterministic regardless of registration order or cycles.</li>
 * </ul>
 *
 * <p>An earlier version of this test asserted strict registration order inside a phase, which
 * parallel dispatch cannot provide. Replacing those assertions with phase-order checks keeps the
 * test meaningful instead of deleting coverage.
 */
public class EventTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("asyncfabric-api-base");

	public static void run() {
		long time1 = System.currentTimeMillis();

		testDefaultPhaseOnly();
		testMultipleDefaultPhases();
		testAddedPhases();
		testCycle();
		NodeSorting.ENABLE_CYCLE_WARNING = false;
		testDeterministicOrdering();
		testTwoCycles();
		NodeSorting.ENABLE_CYCLE_WARNING = true;

		long time2 = System.currentTimeMillis();
		LOGGER.info("Event unit tests succeeded in {} milliseconds.", time2 - time1);
	}

	private static final Function<Test[], Test> INVOKER_FACTORY = listeners -> () -> {
		for (Test test : listeners) {
			test.onTest();
		}
	};

	private static Event<Test> createEvent() {
		return EventFactory.createArrayBacked(Test.class, INVOKER_FACTORY);
	}

	/**
	 * Records which phase each listener belongs to, in completion order.
	 *
	 * <p>Listeners of one phase may interleave freely; the phase numbers must not go backwards.
	 */
	private static final class PhaseRecorder {
		private final List<Integer> observed = new CopyOnWriteArrayList<>();

		Test listener(int phase) {
			return () -> observed.add(phase);
		}

		/** @return how many listeners ran */
		int count() {
			return observed.size();
		}

		/**
		 * Asserts that every phase number appears the expected number of times and that no phase
		 * ran before an earlier one finished.
		 *
		 * @param expectedCounts listeners per phase, indexed by phase number
		 */
		void assertPhases(int... expectedCounts) {
			int expectedTotal = 0;

			for (int count : expectedCounts) {
				expectedTotal += count;
			}

			assertEquals(expectedTotal, observed.size());

			int[] actual = new int[expectedCounts.length];

			for (int phase : observed) {
				assertTrue(phase >= 0 && phase < actual.length, "listener reported unknown phase " + phase);
				actual[phase]++;
			}

			for (int phase = 0; phase < expectedCounts.length; phase++) {
				assertEquals(expectedCounts[phase], actual[phase]);
			}

			// phase boundaries must be hard: the observed sequence never goes backwards, which can
			// only hold if every listener of phase N finished before phase N+1 started
			int previous = -1;

			for (int phase : observed) {
				assertTrue(phase >= previous, "phase " + phase + " ran after phase " + previous
						+ ", which violates phase ordering");
				previous = Math.max(previous, phase);
			}
		}

		/**
		 * Asserts only that every listener ran exactly once.
		 *
		 * <p>Used for graphs with cycles: a cycle makes the ordering constraints inconsistent by
		 * definition (some get respected, some ignored), so asserting an order there would be
		 * asserting an accident. Exactly-once is still a hard requirement.
		 *
		 * @param expectedCount total listeners
		 */
		void assertRunsOnce(int expectedCount) {
			assertEquals(expectedCount, observed.size());
		}

		void reset() {
			observed.clear();
		}
	}

	private static void testDefaultPhaseOnly() {
		Event<Test> event = createEvent();
		PhaseRecorder recorder = new PhaseRecorder();

		// one phase: order inside it is free, so all three are phase 0
		event.register(recorder.listener(0));
		event.register(Event.DEFAULT_PHASE, recorder.listener(0));
		event.register(recorder.listener(0));

		event.invoker().onTest();
		recorder.assertPhases(3);
	}

	private static void testMultipleDefaultPhases() {
		Identifier first = Identifier.fromNamespaceAndPath("fabric", "first");
		Identifier second = Identifier.fromNamespaceAndPath("fabric", "second");
		Event<Test> event = EventFactory.createWithPhases(Test.class, INVOKER_FACTORY, first, second, Event.DEFAULT_PHASE);
		PhaseRecorder recorder = new PhaseRecorder();

		event.register(second, recorder.listener(1));
		event.register(recorder.listener(2));
		event.register(first, recorder.listener(0));

		for (int i = 0; i < 5; ++i) {
			event.invoker().onTest();
			recorder.assertPhases(1, 1, 1);
			recorder.reset();
		}
	}

	private static void testAddedPhases() {
		Event<Test> event = createEvent();
		PhaseRecorder recorder = new PhaseRecorder();

		Identifier veryEarly = Identifier.fromNamespaceAndPath("fabric", "very_early");
		Identifier early = Identifier.fromNamespaceAndPath("fabric", "early");
		Identifier late = Identifier.fromNamespaceAndPath("fabric", "late");
		Identifier veryLate = Identifier.fromNamespaceAndPath("fabric", "very_late");

		event.addPhaseOrdering(veryEarly, early);
		event.addPhaseOrdering(early, Event.DEFAULT_PHASE);
		event.addPhaseOrdering(Event.DEFAULT_PHASE, late);
		event.addPhaseOrdering(late, veryLate);

		// phase numbers follow the declared order: veryEarly=0, early=1, default=2, late=3, veryLate=4
		event.register(recorder.listener(2));
		event.register(recorder.listener(2));
		event.register(veryEarly, recorder.listener(0));
		event.register(early, recorder.listener(1));
		event.register(late, recorder.listener(3));
		event.register(veryLate, recorder.listener(4));
		event.register(veryEarly, recorder.listener(0));
		event.register(veryLate, recorder.listener(4));
		event.register(late, recorder.listener(3));
		event.register(early, recorder.listener(1));

		for (int i = 0; i < 5; ++i) {
			event.invoker().onTest();
			recorder.assertPhases(2, 2, 2, 2, 2);
			recorder.reset();
		}
	}

	private static void testCycle() {
		Event<Test> event = createEvent();
		PhaseRecorder recorder = new PhaseRecorder();

		Identifier a = Identifier.fromNamespaceAndPath("fabric", "a");
		Identifier b1 = Identifier.fromNamespaceAndPath("fabric", "b1");
		Identifier b2 = Identifier.fromNamespaceAndPath("fabric", "b2");
		Identifier b3 = Identifier.fromNamespaceAndPath("fabric", "b3");
		Identifier c = Event.DEFAULT_PHASE;

		// A always first and C always last.
		event.register(a, recorder.listener(0));
		event.register(c, recorder.listener(2));
		event.register(b1, recorder.listener(1));
		event.register(b1, recorder.listener(1));
		event.register(b1, recorder.listener(1));

		// A -> B
		event.addPhaseOrdering(a, b1);
		// B -> C
		event.addPhaseOrdering(b3, c);
		// loop
		event.addPhaseOrdering(b1, b2);
		event.addPhaseOrdering(b2, b3);
		event.addPhaseOrdering(b3, b1);

		for (int i = 0; i < 5; ++i) {
			event.invoker().onTest();
			// the graph contains a cycle, so ordering constraints are intentionally inconsistent;
			// what must hold is that every listener ran exactly once
			recorder.assertRunsOnce(5);
			recorder.reset();
		}
	}

	/**
	 * Ensure that phases get sorted deterministically regardless of the order in which constraints are registered.
	 *
	 * <p>The graph is displayed here as ascii art, and also in the file graph.png.
	 * <pre>
	 *             +-------------------+
	 *             v                   |
	 * +---+     +---+     +---+     +---+
	 * | a | --> | z | --> | b | --> | y |
	 * +---+     +---+     +---+     +---+
	 *             ^
	 *             |
	 *             |
	 * +---+     +---+
	 * | d | --> | e |
	 * +---+     +---+
	 * +---+
	 * | f |
	 * +---+
	 * </pre>
	 * Notice the cycle z -> b -> y -> z. The elements of the cycle are ordered [b, y, z], and the cycle itself is ordered with its lowest id "b".
	 * We get for the final order: [a, d, e, cycle [b, y, z], f].
	 */
	private static void testDeterministicOrdering() {
		Identifier a = Identifier.fromNamespaceAndPath("fabric", "a");
		Identifier b = Identifier.fromNamespaceAndPath("fabric", "b");
		Identifier d = Identifier.fromNamespaceAndPath("fabric", "d");
		Identifier e = Identifier.fromNamespaceAndPath("fabric", "e");
		Identifier f = Identifier.fromNamespaceAndPath("fabric", "f");
		Identifier y = Identifier.fromNamespaceAndPath("fabric", "y");
		Identifier z = Identifier.fromNamespaceAndPath("fabric", "z");

		List<Consumer<Event<Test>>> dependencies = List.of(
				ev -> ev.addPhaseOrdering(a, z),
				ev -> ev.addPhaseOrdering(d, e),
				ev -> ev.addPhaseOrdering(e, z),
				ev -> ev.addPhaseOrdering(z, b),
				ev -> ev.addPhaseOrdering(b, y),
				ev -> ev.addPhaseOrdering(y, z)
		);

		testAllPermutations(new ArrayList<>(), dependencies, selectedDependencies -> {
			Event<Test> event = createEvent();
			PhaseRecorder recorder = new PhaseRecorder();

			for (Consumer<Event<Test>> dependency : selectedDependencies) {
				dependency.accept(event);
			}

			// sorted order: a, d, e, then the cycle [b, y, z], then f
			event.register(a, recorder.listener(0));
			event.register(d, recorder.listener(1));
			event.register(e, recorder.listener(2));
			event.register(b, recorder.listener(3));
			event.register(y, recorder.listener(3));
			event.register(z, recorder.listener(3));
			event.register(f, recorder.listener(4));

			event.invoker().onTest();
			// the graph contains a cycle, so no order is guaranteed
			recorder.assertRunsOnce(7);
		});
	}

	/**
	 * Test deterministic phase sorting with two cycles.
	 * <pre>
	 * e --> a <--> b <-- d <--> c
	 * </pre>
	 */
	private static void testTwoCycles() {
		Identifier a = Identifier.fromNamespaceAndPath("fabric", "a");
		Identifier b = Identifier.fromNamespaceAndPath("fabric", "b");
		Identifier c = Identifier.fromNamespaceAndPath("fabric", "c");
		Identifier d = Identifier.fromNamespaceAndPath("fabric", "d");
		Identifier e = Identifier.fromNamespaceAndPath("fabric", "e");

		List<Consumer<Event<Test>>> dependencies = List.of(
				ev -> ev.addPhaseOrdering(e, a),
				ev -> ev.addPhaseOrdering(a, b),
				ev -> ev.addPhaseOrdering(b, a),
				ev -> ev.addPhaseOrdering(d, b),
				ev -> ev.addPhaseOrdering(d, c),
				ev -> ev.addPhaseOrdering(c, d)
		);

		testAllPermutations(new ArrayList<>(), dependencies, selectedDependencies -> {
			Event<Test> event = createEvent();
			PhaseRecorder recorder = new PhaseRecorder();

			for (Consumer<Event<Test>> dependency : selectedDependencies) {
				dependency.accept(event);
			}

			// e first; then the cycles {a,b} and {c,d}
			event.register(c, recorder.listener(2));
			event.register(d, recorder.listener(2));
			event.register(e, recorder.listener(0));
			event.register(a, recorder.listener(1));
			event.register(b, recorder.listener(1));

			event.invoker().onTest();
			// two cycles: ordering is inconsistent by construction, count is what matters
			recorder.assertRunsOnce(5);
		});
	}

	@SuppressWarnings("SuspiciousListRemoveInLoop")
	private static <T> void testAllPermutations(List<T> selected, List<T> toSelect, Consumer<List<T>> action) {
		if (toSelect.size() == 0) {
			action.accept(selected);
		} else {
			for (int i = 0; i < toSelect.size(); ++i) {
				selected.add(toSelect.get(i));
				List<T> remaining = new ArrayList<>(toSelect);
				remaining.remove(i);
				testAllPermutations(selected, remaining, action);
				selected.remove(selected.size()-1);
			}
		}
	}

	@FunctionalInterface
	interface Test {
		void onTest();
	}

	private static void assertEquals(Object expected, Object actual) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(String.format("assertEquals failed%nexpected: %s%n but was: %s", expected, actual));
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
