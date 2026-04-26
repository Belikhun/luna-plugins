package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.exception.CoreServiceException;

public final class LunaCoreNeoForge {
	private static LunaCoreNeoForgeServices services;

	private LunaCoreNeoForge() {
	}

	public static LunaCoreNeoForgeServices services() {
		if (services == null) {
			throw new CoreServiceException("LunaCoreNeoForge services are not initialized yet.");
		}

		return services;
	}

	static void set(LunaCoreNeoForgeServices coreServices) {
		services = coreServices;
	}

	static void clear() {
		services = null;
	}
}
