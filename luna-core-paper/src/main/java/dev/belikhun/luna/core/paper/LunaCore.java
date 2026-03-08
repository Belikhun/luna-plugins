package dev.belikhun.luna.core.paper;

import dev.belikhun.luna.core.api.exception.CoreServiceException;

public final class LunaCore {
	private static LunaCoreServices services;

	private LunaCore() {
	}

	public static LunaCoreServices services() {
		if (services == null) {
			throw new CoreServiceException("LunaCore services are not initialized yet.");
		}

		return services;
	}

	static void set(LunaCoreServices coreServices) {
		services = coreServices;
	}

	static void clear() {
		services = null;
	}
}


