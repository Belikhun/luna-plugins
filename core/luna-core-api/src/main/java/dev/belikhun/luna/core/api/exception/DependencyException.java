package dev.belikhun.luna.core.api.exception;

public final class DependencyException extends LunaCoreException {
	public DependencyException(String message) {
		super(message);
	}

	public DependencyException(String message, Throwable cause) {
		super(message, cause);
	}
}

