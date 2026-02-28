package dev.belikhun.luna.core.api.exception;

public final class MigrationException extends LunaCoreException {
	public MigrationException(String message) {
		super(message);
	}

	public MigrationException(String message, Throwable cause) {
		super(message, cause);
	}
}
