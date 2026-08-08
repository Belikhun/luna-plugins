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
	long updatedAtEpochMillis,
	long temporaryPasswordUntilEpochMillis
) {
	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank();
	}

	public boolean isLocked(long nowEpochMillis) {
		return lockoutUntilEpochMillis > nowEpochMillis;
	}

	/**
	 * Whether the stored password is one an administrator issued with an expiry
	 * attached. A password the player chose themselves never carries one.
	 */
	public boolean hasTemporaryPassword() {
		return temporaryPasswordUntilEpochMillis > 0L;
	}

	/** Whether that temporary password has run out and no longer grants access. */
	public boolean temporaryPasswordExpired(long nowEpochMillis) {
		return hasTemporaryPassword() && nowEpochMillis >= temporaryPasswordUntilEpochMillis;
	}
}
