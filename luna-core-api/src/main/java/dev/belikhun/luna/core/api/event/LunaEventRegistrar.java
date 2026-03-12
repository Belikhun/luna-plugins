package dev.belikhun.luna.core.api.event;

public interface LunaEventRegistrar {
	<E> void registerListener(Class<E> eventType, LunaEventListener<? super E> listener);

	<E> void unregisterListener(Class<E> eventType, LunaEventListener<? super E> listener);
}
