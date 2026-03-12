package dev.belikhun.luna.core.api.event;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public final class LunaEventManager implements LunaEventRegistrar, LunaEventDispatcher {
	private final Map<Class<?>, Set<LunaEventListener<?>>> listeners;

	public LunaEventManager() {
		this.listeners = new ConcurrentHashMap<>();
	}

	@Override
	public <E> void registerListener(Class<E> eventType, LunaEventListener<? super E> listener) {
		if (eventType == null || listener == null) {
			return;
		}

		listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArraySet<>()).add(listener);
	}

	@Override
	public <E> void unregisterListener(Class<E> eventType, LunaEventListener<? super E> listener) {
		if (eventType == null || listener == null) {
			return;
		}

		Set<LunaEventListener<?>> group = listeners.get(eventType);
		if (group == null) {
			return;
		}

		group.remove(listener);
		if (group.isEmpty()) {
			listeners.remove(eventType, group);
		}
	}

	@Override
	public void dispatchEvent(Object event) {
		if (event == null) {
			return;
		}

		Class<?> eventType = event.getClass();
		for (Map.Entry<Class<?>, Set<LunaEventListener<?>>> entry : listeners.entrySet()) {
			if (!entry.getKey().isAssignableFrom(eventType)) {
				continue;
			}

			for (LunaEventListener<?> listener : entry.getValue()) {
				invoke(listener, event);
			}
		}
	}

	public void clear() {
		listeners.clear();
	}

	@SuppressWarnings("unchecked")
	private void invoke(LunaEventListener<?> listener, Object event) {
		((LunaEventListener<Object>) listener).onEvent(event);
	}
}
