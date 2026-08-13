package dev.belikhun.luna.legacy.exception;

public final class MigrationException extends LunaLegacyException {
	private static final long serialVersionUID = 1L;

	public MigrationException(String message) {
		super(message);
	}

	public MigrationException(String message, Throwable cause) {
		super(message, cause);
	}
}

