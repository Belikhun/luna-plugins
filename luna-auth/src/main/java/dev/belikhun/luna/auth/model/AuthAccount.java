package dev.belikhun.luna.auth.model;

import java.util.UUID;

public record AuthAccount(
	UUID playerUuid,
	String username,
	String passwordHash,
	String lastIp,
	int failedAttempts,
	long lockoutUntilEpochMillis,
	long lastLoginAtEpochMillis,
	long createdAtEpochMillis,
	long updatedAtEpochMillis
) {
	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank();
	}

	public boolean isLocked(long nowEpochMillis) {
		return lockoutUntilEpochMillis > nowEpochMillis;
	}
}
