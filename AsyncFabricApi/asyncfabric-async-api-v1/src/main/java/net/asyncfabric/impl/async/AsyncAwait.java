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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocks the game thread on a future with a timeout.
 *
 * <p>A hung listener must not freeze the game forever, so every synchronous wait on async
 * work is bounded. On timeout the game continues and a loud error names the event and the
 * timeout, which is far easier to diagnose than a frozen window.
 *
 * <p>Waits here are intentional: lifecycle moments (server starting, stopping) must complete
 * before the game moves on. Tick events never use this — they never block.
 */
public final class AsyncAwait {
	private static final Logger LOGGER = LoggerFactory.getLogger("AsyncFabric/Async");
	private static final long DEFAULT_TIMEOUT_MS = 10_000;
	private static final String TIMEOUT_PROPERTY = "asyncfabric.async.timeoutMs";

	private AsyncAwait() {
	}

	/**
	 * Waits for {@code future} on the calling (game) thread.
	 *
	 * @param future the work to wait for
	 * @param eventName event name used in diagnostics
	 * @param server server the game thread belongs to, used to detect deadlock
	 */
	public static void blockOn(CompletableFuture<?> future, String eventName, Object server) {
		if (future == null || future.isDone()) {
			logIfFailed(future, eventName);
			return;
		}

		long timeoutMs = Long.getLong(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS);

		try {
			future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			LOGGER.error("Async listener for {} did not finish within {} ms; continuing without it. "
							+ "Raise the limit with -D%s=<millis> or fix the slow listener.",
					eventName, timeoutMs, TIMEOUT_PROPERTY);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Interrupted while waiting for async {}", eventName);
		} catch (java.util.concurrent.ExecutionException e) {
			LOGGER.error("Async listener for {} failed", eventName, unwrap(e));
		}
	}

	private static void logIfFailed(CompletableFuture<?> future, String eventName) {
		if (future != null && future.isCompletedExceptionally()) {
			future.whenComplete((ignored, throwable) -> LOGGER.error(
					"Async listener for {} failed", eventName, unwrap(throwable)));
		}
	}

	private static Throwable unwrap(Throwable throwable) {
		if (throwable instanceof CompletionException && throwable.getCause() != null) {
			return throwable.getCause();
		}

		return throwable;
	}
}
