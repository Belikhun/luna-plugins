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

	/**
	 * Whether {@link #services()} would answer rather than throw.
	 *
	 * Fabric gives every mod the same server-started event and no ordering a mod
	 * can rely on - a dependant whose id sorts first is called first - so a module
	 * that needs the core asks this and waits for a later tick rather than
	 * treating a missing core as a crash.
	 */
	public static boolean isReady() {
		return services != null;
	}

	public static void set(LunaCoreFabricServices coreServices) {
		services = coreServices;
	}

	public static void clear() {
		services = null;
	}
}
