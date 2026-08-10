package dev.belikhun.luna.core.mc;

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

	/**
	 * Whether {@link #services()} would answer rather than throw.
	 *
	 * A module that starts before the core has published its services asks this
	 * and waits, rather than treating a missing core as a crash.
	 */
	public static boolean isReady() {
		return services != null;
	}

	public static void set(LunaCoreServices coreServices) {
		services = coreServices;
	}

	public static void clear() {
		services = null;
	}
}
