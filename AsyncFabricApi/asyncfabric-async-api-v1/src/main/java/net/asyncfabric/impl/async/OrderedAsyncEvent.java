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

import java.util.function.Function;

import net.minecraft.resources.Identifier;

/**
 * Event whose listeners run one at a time, each observing the previous one's result.
 *
 * <p>This is the async form of the synchronous "loop over listeners" invoker: the
 * combiner turns the ordered listener array into a {@code thenCompose} chain, so
 * short-circuiting semantics (cancel, first-wins, transform) survive while the game
 * thread stays free.
 */
public final class OrderedAsyncEvent<T> extends AbstractAsyncEvent<T> {
	public OrderedAsyncEvent(Class<T> type, Function<T[], T> chainer) {
		super(type, chainer);
	}

	public static void ensureContainsDefault(Identifier[] defaultPhases) {
		AbstractAsyncEvent.ensureContainsDefault(defaultPhases);
	}

	public static void ensureNoDuplicates(Identifier[] defaultPhases) {
		AbstractAsyncEvent.ensureNoDuplicates(defaultPhases);
	}
}
