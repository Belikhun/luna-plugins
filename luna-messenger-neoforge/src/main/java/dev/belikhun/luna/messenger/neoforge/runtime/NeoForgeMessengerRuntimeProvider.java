package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;

public interface NeoForgeMessengerRuntimeProvider {
	String name();

	int priority();

	NeoForgeMessengerRuntime createRuntime(LunaLogger logger, DependencyManager dependencyManager, BackendPlaceholderResolver fallbackResolver);

	default BackendPlaceholderResolver createPlaceholderResolver(LunaLogger logger, DependencyManager dependencyManager) {
		return null;
	}
}
