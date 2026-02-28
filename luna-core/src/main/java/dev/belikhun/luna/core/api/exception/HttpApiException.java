package dev.belikhun.luna.core.api.exception;

public class HttpApiException extends LunaCoreException {
	public HttpApiException(String message) {
		super(message);
	}

	public HttpApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
