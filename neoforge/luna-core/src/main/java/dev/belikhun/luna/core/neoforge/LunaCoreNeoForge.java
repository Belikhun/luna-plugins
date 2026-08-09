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

	/**
	 * Whether {@link #services()} would answer rather than throw.
	 *
	 * A module that starts before the core has published its services asks this
	 * and waits, rather than treating a missing core as a crash.
	 */
	public static boolean isReady() {
		return services != null;
	}

	public static void set(LunaCoreNeoForgeServices coreServices) {
		services = coreServices;
	}

	public static void clear() {
		services = null;
	}
}
