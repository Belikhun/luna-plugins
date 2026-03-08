package dev.belikhun.luna.core.api.exception;

public final class ControllerRegistrationException extends HttpApiException {
	public ControllerRegistrationException(String message) {
		super(message);
	}

	public ControllerRegistrationException(String message, Throwable cause) {
		super(message, cause);
	}
}

