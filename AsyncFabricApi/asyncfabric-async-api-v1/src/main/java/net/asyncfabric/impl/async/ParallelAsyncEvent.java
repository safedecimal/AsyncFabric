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

/**
 * Event whose listeners all start at once and whose results are reduced afterwards.
 *
 * <p>Listeners never observe each other's results, so this is only for independent work
 * (tick callbacks, notifications, background computation). Anything order-sensitive must
 * use {@link OrderedAsyncEvent} instead.
 *
 * <p>Phase order still applies: the array handed to the combiner is phase-ordered, so a
 * reducer that respects array order produces a deterministic result even though the
 * listeners ran concurrently.
 */
public final class ParallelAsyncEvent<T> extends AbstractAsyncEvent<T> {
	public ParallelAsyncEvent(Class<T> type, Function<T[], T> fanOut) {
		super(type, fanOut);
	}
}
