package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.exception.CoreServiceException;

/** Entry point other luna fabric modules resolve the core's services through. */
public final class LunaCoreFabric {
	private static LunaCoreFabricServices services;

	private LunaCoreFabric() {
	}

	public static LunaCoreFabricServices services() {
		if (services == null) {
			throw new CoreServiceException("LunaCoreFabric services are not initialized yet.");
		}

		return services;
	}

	public static void set(LunaCoreFabricServices coreServices) {
		services = coreServices;
	}

	public static void clear() {
		services = null;
	}
}
