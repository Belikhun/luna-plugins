package dev.belikhun.luna.core.velocity;

import dev.belikhun.luna.core.api.exception.CoreServiceException;

public final class LunaCoreVelocity {
	private static LunaCoreVelocityServices services;

	private LunaCoreVelocity() {
	}

	public static LunaCoreVelocityServices services() {
		if (services == null) {
			throw new CoreServiceException("LunaCoreVelocity services are not initialized yet.");
		}

		return services;
	}

	static void set(LunaCoreVelocityServices coreServices) {
		services = coreServices;
	}

	static void clear() {
		services = null;
	}
}
