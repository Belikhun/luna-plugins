package dev.belikhun.luna.countdown.neoforge.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;

public interface NeoForgeCountdownRuntimeProvider {
	String name();

	int priority();

	NeoForgeCountdownScheduler createScheduler(LunaLogger logger);

	NeoForgeCountdownNotifier createNotifier(LunaLogger logger);
}
