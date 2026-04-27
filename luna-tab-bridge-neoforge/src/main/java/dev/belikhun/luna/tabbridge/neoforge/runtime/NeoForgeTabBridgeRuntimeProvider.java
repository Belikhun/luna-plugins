package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;

public interface NeoForgeTabBridgeRuntimeProvider {
	String name();

	int priority();

	NeoForgeTabBridgeRuntime createRuntime(LunaLogger logger, DependencyManager dependencyManager);
}
