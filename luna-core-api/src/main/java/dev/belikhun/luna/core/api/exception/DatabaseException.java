package dev.belikhun.luna.core.api.exception;

public class DatabaseException extends LunaCoreException {
	public DatabaseException(String message) {
		super(message);
	}

	public DatabaseException(String message, Throwable cause) {
		super(message, cause);
	}
}

