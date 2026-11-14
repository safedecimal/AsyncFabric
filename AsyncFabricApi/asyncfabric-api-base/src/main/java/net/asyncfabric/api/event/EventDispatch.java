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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which thread an event's listeners run on.
 *
 * <p>Put it on the listener interface, not on the event constant:
 *
 * <pre>{@code
 * @EventDispatch(EventThreadMode.MAIN_THREAD)
 * public interface EndFrame {
 *     void onEndFrame();
 * }
 * }</pre>
 *
 * <p>The default is {@link EventThreadMode#AUTO}: listeners are expected to be thread-safe and
 * are spread over the dispatch pool. Mark an event {@link EventThreadMode#MAIN_THREAD} when its
 * listeners touch things that are not safe off the game thread — the render pipeline, the
 * OpenGL context, window state, or world and registry mutation without a hand-off.
 *
 * @see EventThreadMode
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventDispatch {
	/**
	 * @return where this event's listeners run
	 */
	EventThreadMode value() default EventThreadMode.AUTO;
}
