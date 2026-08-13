package dev.belikhun.luna.legacy.exception;

public class DatabaseException extends LunaLegacyException {
	private static final long serialVersionUID = 1L;

	public DatabaseException(String message) {
		super(message);
	}

	public DatabaseException(String message, Throwable cause) {
		super(message, cause);
	}
}

