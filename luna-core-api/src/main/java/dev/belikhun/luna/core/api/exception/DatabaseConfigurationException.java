package dev.belikhun.luna.core.api.exception;

public final class DatabaseConfigurationException extends DatabaseException {
	public DatabaseConfigurationException(String message) {
		super(message);
	}

	public DatabaseConfigurationException(String message, Throwable cause) {
		super(message, cause);
	}
}

