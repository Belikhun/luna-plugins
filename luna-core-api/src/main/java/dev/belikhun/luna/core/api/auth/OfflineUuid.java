package dev.belikhun.luna.core.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OfflineUuid {
	private OfflineUuid() {
	}

	public static UUID fromUsername(String username) {
		String normalized = username == null ? "" : username;
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + normalized).getBytes(StandardCharsets.UTF_8));
	}
}
