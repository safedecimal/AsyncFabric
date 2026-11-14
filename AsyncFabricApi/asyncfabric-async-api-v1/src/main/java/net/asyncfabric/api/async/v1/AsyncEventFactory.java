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

import java.util.function.Function;

import net.minecraft.resources.Identifier;

import net.asyncfabric.impl.async.OrderedAsyncEvent;
import net.asyncfabric.impl.async.ParallelAsyncEvent;

/**
 * Creates {@link AsyncEvent} instances.
 *
 * <p>Both factories take a "combiner" function. It receives the phase-ordered array of
 * registered listeners and returns a single listener that drives all of them, exactly
 * like {@link net.asyncfabric.api.event.EventFactory#createArrayBacked}. The difference
 * is that the combiner builds a chain of {@code CompletableFuture} stages instead of
 * looping synchronously.
 */
public final class AsyncEventFactory {
	private AsyncEventFactory() {
	}

	/**
	 * Creates an event whose listeners run <em>one at a time</em>, in phase order.
	 *
	 * <p>The combiner receives the ordered listeners and must return a listener that
	 * waits for each one before starting the next. Each listener observes the previous
	 * listener's result, which is what cancellable events and value transformations need.
	 *
	 * <pre>{@code
	 * public static final AsyncEvent<AllowDamage> ALLOW_DAMAGE = AsyncEventFactory.createOrdered(
	 *     AllowDamage.class,
	 *     listeners -> (entity, source, amount) -> {
	 *         CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
	 *
	 *         for (AllowDamage listener : listeners) {
	 *             result = result.thenCompose(allow -> allow
	 *                 ? listener.allowDamage(entity, source, amount)
	 *                 : CompletableFuture.completedFuture(false));
	 *         }
	 *
	 *         return result;
	 *     });
	 * }</pre>
	 *
	 * @param type the listener type
	 * @param chainer builds the sequential invoker from the ordered listeners
	 * @param <T> the listener type
	 * @return the new event
	 */
	public static <T> AsyncEvent<T> createOrdered(Class<T> type, Function<T[], T> chainer) {
		return new OrderedAsyncEvent<>(type, chainer);
	}

	/**
	 * Creates an event whose listeners run <em>all at once</em> on the
	 * {@linkplain AsyncScheduler scheduler}.
	 *
	 * <p>The combiner receives the ordered listeners and must return a listener that
	 * starts every listener, then reduces their futures into one. Listeners do not see
	 * each other's results, so this is only suitable for independent work.
	 *
	 * <pre>{@code
	 * public static final AsyncEvent<StartTick> START_TICK = AsyncEventFactory.createParallel(
	 *     StartTick.class,
	 *     listeners -> server -> {
	 *         List<CompletableFuture<Void>> futures = new ArrayList<>(listeners.length);
	 *
	 *         for (StartTick listener : listeners) {
	 *             futures.add(AsyncScheduler.supplyUnchecked(() -> listener.onStartTick(server)))
	 *                 .stream().flatMap(...); // see AsyncScheduler.joinAll
	 *         }
	 *
	 *         return AsyncScheduler.joinAll(futures);
	 *     });
	 * }</pre>
	 *
	 * @param type the listener type
	 * @param fanOut builds the fan-out-and-reduce invoker from the ordered listeners
	 * @param <T> the listener type
	 * @return the new event
	 */
	public static <T> AsyncEvent<T> createParallel(Class<T> type, Function<T[], T> fanOut) {
		return new ParallelAsyncEvent<>(type, fanOut);
	}

	/**
	 * Creates an event that supports ordering listeners into named phases.
	 *
	 * @param type the listener type
	 * @param chainer builds the sequential invoker from the ordered listeners
	 * @param defaultPhases the phases to create up front, must contain {@link AsyncEvent#DEFAULT_PHASE}
	 * @param <T> the listener type
	 * @return the new event
	 */
	public static <T> AsyncEvent<T> createOrderedWithPhases(Class<T> type, Function<T[], T> chainer, Identifier... defaultPhases) {
		OrderedAsyncEvent.ensureContainsDefault(defaultPhases);
		OrderedAsyncEvent.ensureNoDuplicates(defaultPhases);
		OrderedAsyncEvent<T> event = new OrderedAsyncEvent<>(type, chainer);

		for (Identifier phase : defaultPhases) {
			event.addPhase(phase);
		}

		return event;
	}
}
