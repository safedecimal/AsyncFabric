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

package net.asyncfabric.impl.base.event;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import net.asyncfabric.api.event.EventDispatch;
import net.asyncfabric.api.event.EventThreadMode;

/**
 * Turns a synchronous event invoker into a fan-out dispatcher without changing its call shape.
 *
 * <p>Fabric's invokers are generated lambdas of the shape
 * {@code listeners -> args -> { for (L l : listeners) l.handle(args); }}. There are hundreds of
 * them across the API, so rewriting each one is neither practical nor safe. This class works one
 * level lower: the listener array handed to the factory is filled with deferring proxies, so the
 * untouched loop hands each listener to the worker pool. That is what takes every module off the
 * single thread without editing a single invoker lambda.
 *
 * <p><b>Phases stay ordered.</b> Phases exist to express ordering between groups of listeners, so
 * the last listener of each phase waits for the rest of its own phase before returning. The
 * untouched loop therefore still walks phase by phase, but listeners <em>inside</em> a phase run
 * concurrently. Ordering between listeners of the same phase is not guaranteed.
 *
 * <p>Only {@code void} listeners are deferred. A listener that returns a value has to hand it back
 * to the loop synchronously — a cancellation flag, a transformed stack, a computed slot — so
 * deferring it would change semantics. Those keep running inline, in order.
 *
 * <p>Nesting is safe: an event emitted from inside a dispatch runs inline, so a listener can never
 * wait on work queued behind itself.
 */
public final class EventDispatchEngine {
	private EventDispatchEngine() {
	}

	/**
	 * Builds the invoker published on the event, parallelising it when the listener type allows.
	 *
	 * @param factory the event's invoker factory, as passed to {@code EventFactory}
	 * @param listeners the phase-ordered listener array
	 * @param listenerClass the listener interface
	 * @param phaseSizes listener count per phase, in invocation order, or {@code null} for a
	 *                   single-phase event
	 * @param <T> listener type
	 * @return the invoker to publish as {@code event.invoker()}
	 */
	static <T> T createInvoker(Function<T[], T> factory, T[] listeners, Class<? super T> listenerClass,
			int[] phaseSizes) {
		if (listeners.length < 2 || !isParallel(listenerClass)) {
			return factory.apply(listeners);
		}

		Method handler = findHandlerMethod(listenerClass);

		if (handler == null) {
			return factory.apply(listeners);
		}

		handler = makeInvocable(handler);

		@SuppressWarnings("unchecked")
		T[] deferred = (T[]) Array.newInstance(listenerClass, listeners.length);

		// one gate per phase; the last listener of a phase closes it, which is the barrier that
		// keeps the untouched loop's phase-by-phase traversal meaningful
		int[] sizes = phaseSizes != null ? phaseSizes : new int[] { listeners.length };
		int index = 0;

		for (int size : sizes) {
			if (size <= 0) continue;

			PhaseGate gate = new PhaseGate();

			for (int i = 0; i < size && index < listeners.length; i++, index++) {
				deferred[index] = newDeferringProxy(listenerClass, listeners[index], handler, gate,
						i == size - 1);
			}
		}

		// listeners beyond the reported phase sizes still need a proxy; treat each as its own phase
		for (; index < listeners.length; index++) {
			deferred[index] = newDeferringProxy(listenerClass, listeners[index], handler, new PhaseGate(), true);
		}

		return barrier(listenerClass, factory.apply(deferred));
	}

	/** @return {@code true} if this listener type may run off the emitting thread */
	private static boolean isParallel(Class<?> listenerClass) {
		if (modeOf(listenerClass) != EventThreadMode.AUTO) return false;

		// value-returning listeners feed their result back into the loop; deferring breaks that
		return hasVoidHandler(listenerClass);
	}

	/** @return the declared dispatch mode of {@code listenerClass} */
	static EventThreadMode modeOf(Class<?> listenerClass) {
		EventDispatch annotation = listenerClass.getAnnotation(EventDispatch.class);

		return annotation != null ? annotation.value() : EventThreadMode.AUTO;
	}

	static boolean hasVoidHandler(Class<?> listenerClass) {
		Method handler = findHandlerMethod(listenerClass);

		return handler != null && handler.getReturnType() == void.class;
	}

	/**
	 * @return the single abstract method of the listener interface, or {@code null} if the type is
	 *         not a usable functional interface
	 */
	static Method findHandlerMethod(Class<?> listenerClass) {
		Method handler = null;

		for (Method method : listenerClass.getMethods()) {
			if (Modifier.isStatic(method.getModifiers()) || method.isDefault()) continue;
			if (method.getDeclaringClass() == Object.class) continue;

			if (handler != null && !sameSignature(handler, method)) {
				return null; // more than one abstract method: not a functional interface
			}

			handler = method;
		}

		return handler;
	}

	private static boolean sameSignature(Method a, Method b) {
		return a.getName().equals(b.getName()) && a.getParameterCount() == b.getParameterCount();
	}

	@SuppressWarnings("unchecked")
	private static <T> T newDeferringProxy(Class<? super T> listenerClass, T listener, Method handler,
			PhaseGate gate, boolean lastOfPhase) {
		ClassLoader loader = listenerClass.getClassLoader();

		return (T) Proxy.newProxyInstance(loader, new Class<?>[] { listenerClass },
				new DeferringHandler(listener, handler, gate, lastOfPhase));
	}

	/**
	 * Wraps {@code invoker} so one emission reports listener failures after the loop finished.
	 *
	 * <p>Only the outermost emission does bookkeeping; nested ones run inline.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T barrier(Class<? super T> listenerClass, T invoker) {
		return (T) Proxy.newProxyInstance(listenerClass.getClassLoader(),
				new Class<?>[] { listenerClass },
				(proxy, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						return callReflectively(listenerClass.cast(invoker), method, args);
					}

					if (method.isDefault()) {
						return InvocationHandler.invokeDefault(proxy, method, args);
					}

					Method target = makeInvocable(method);

					// nested emission, or already on a worker: run inline so a listener never
					// waits for work queued behind itself
					if (DispatchScope.current() != null || EventWorkerPool.isWorkerThread()) {
						return callReflectively(invoker, target, args);
					}

					DispatchScope scope = new DispatchScope();
					DispatchScope.push(scope);

					Object result;

					try {
						result = callReflectively(invoker, target, args);
					} finally {
						DispatchScope.pop();
					}

					// rethrown after the loop, exactly like vanilla's accumulating invoker
					scope.rethrowFailures();

					return result;
				});
	}

	private static Object callReflectively(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	/**
	 * Listener interfaces are not required to be public (test fixtures, package-private callbacks),
	 * and reflection refuses to invoke those without an explicit opt-in. Done once per handler so
	 * the dispatch path has no per-call check.
	 */
	private static Method makeInvocable(Method handler) {
		try {
			handler.setAccessible(true);
		} catch (RuntimeException e) {
			// a security manager may refuse; invoking may still succeed for public types
		}

		return handler;
	}

	/** One phase's worth of work; its last listener waits for the whole phase. */
	private static final class PhaseGate {
		private final List<CompletableFuture<Void>> pending = new ArrayList<>();

		synchronized void add(CompletableFuture<Void> future) {
			pending.add(future);
		}

		/** Blocks until every listener of this phase finished. */
		void await() {
			CompletableFuture<Void>[] futures;

			synchronized (this) {
				if (pending.isEmpty()) return;

				@SuppressWarnings("unchecked")
				CompletableFuture<Void>[] copy = pending.toArray(new CompletableFuture[0]);
				futures = copy;
				pending.clear();
			}

			for (CompletableFuture<Void> future : futures) {
				try {
					future.join();
				} catch (CompletionException | CancellationException e) {
					// failures were recorded by the worker; the scope reports them once
				}
			}
		}
	}

	/** Collects listener failures for one emission. */
	private static final class DispatchScope {
		private static final ThreadLocal<List<DispatchScope>> STACK = ThreadLocal.withInitial(ArrayList::new);

		private final List<Throwable> failures = new ArrayList<>();

		static DispatchScope current() {
			List<DispatchScope> stack = STACK.get();

			return stack.isEmpty() ? null : stack.get(stack.size() - 1);
		}

		static void push(DispatchScope scope) {
			STACK.get().add(scope);
		}

		static void pop() {
			List<DispatchScope> stack = STACK.get();
			stack.remove(stack.size() - 1);

			if (stack.isEmpty()) STACK.remove();
		}

		synchronized void record(Throwable failure) {
			failures.add(unwrap(failure));
		}

		/** Rethrows the first failure with the others suppressed, like vanilla's invoker did. */
		void rethrowFailures() {
			List<Throwable> collected;

			synchronized (this) {
				if (failures.isEmpty()) return;

				collected = new ArrayList<>(failures);
				failures.clear();
			}

			Throwable first = collected.get(0);

			for (int i = 1; i < collected.size(); i++) {
				Throwable next = collected.get(i);

				if (next != first && !isSuppressed(first, next)) {
					first.addSuppressed(next);
				}
			}

			if (first instanceof RuntimeException) throw (RuntimeException) first;
			if (first instanceof Error) throw (Error) first;

			throw new RuntimeException(first);
		}

		private static boolean isSuppressed(Throwable main, Throwable candidate) {
			for (Throwable suppressed : main.getSuppressed()) {
				if (suppressed == candidate) return true;
			}

			return false;
		}

		/** Unwraps the future and dispatch wrappers, keeping the listener's own exception. */
		private static Throwable unwrap(Throwable t) {
			Throwable current = t;

			while (current.getCause() != null && current.getCause() != current) {
				if (current instanceof CompletionException || current instanceof ExecutionException) {
					current = current.getCause();
					continue;
				}

				break;
			}

			return current;
		}
	}

	/** Hands one listener call to the pool and, at a phase end, waits for that phase. */
	private static final class DeferringHandler implements InvocationHandler {
		private final Object listener;
		private final Method handler;
		private final PhaseGate gate;
		private final boolean lastOfPhase;

		DeferringHandler(Object listener, Method handler, PhaseGate gate, boolean lastOfPhase) {
			this.listener = listener;
			this.handler = handler;
			this.gate = gate;
			this.lastOfPhase = lastOfPhase;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return callReflectively(listener, method, args);
			}

			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}

			DispatchScope scope = DispatchScope.current();

			// outside a dispatch there is no barrier to join: run straight through
			if (scope == null) {
				return callReflectively(listener, method, args);
			}

			Method target = makeInvocable(method);
			ForkJoinPool pool = EventWorkerPool.pool();

			gate.add(CompletableFuture.runAsync(() -> {
				try {
					callReflectively(listener, target, args);
				} catch (Throwable t) {
					scope.record(t);
				}
			}, pool));

			// the phase ends here: wait so the untouched loop stays phase-ordered
			if (lastOfPhase) {
				gate.await();
			}

			return null;
		}
	}
}
