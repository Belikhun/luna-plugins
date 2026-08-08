package dev.belikhun.luna.pack.api;

import java.util.Collection;

@FunctionalInterface
public interface LunaPackDynamicProvider {
	Collection<LunaPackRegistration> provide(LunaPackDynamicContext context);
}
