package dev.belikhun.luna.legacy.exception;

public final class PluginMessagingException extends LunaLegacyException {
	private static final long serialVersionUID = 1L;

	public PluginMessagingException(String message) {
		super(message);
	}

	public PluginMessagingException(String message, Throwable cause) {
		super(message, cause);
	}
}
