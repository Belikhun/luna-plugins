package dev.belikhun.luna.core.api.event;

@FunctionalInterface
public interface LunaEventListener<E> {
	void onEvent(E event);
}
