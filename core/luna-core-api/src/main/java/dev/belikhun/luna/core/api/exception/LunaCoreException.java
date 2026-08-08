package dev.belikhun.luna.core.api.exception;

public class LunaCoreException extends RuntimeException {
	public LunaCoreException(String message) {
		super(message);
	}

	public LunaCoreException(String message, Throwable cause) {
		super(message, cause);
	}
}

