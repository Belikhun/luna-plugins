package dev.belikhun.luna.pack.api;

import dev.belikhun.luna.core.api.event.LunaEventListener;

@FunctionalInterface
public interface LunaPackReloadListener extends LunaEventListener<LunaPackReloadEvent> {
	void onPackReloaded(LunaPackReloadEvent event);

	@Override
	default void onEvent(LunaPackReloadEvent event) {
		onPackReloaded(event);
	}
}
