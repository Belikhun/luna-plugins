package dev.belikhun.luna.legacy.exception;

public final class ConfigStoreException extends LunaLegacyException {
	private static final long serialVersionUID = 1L;

	public ConfigStoreException(String message) {
		super(message);
	}

	public ConfigStoreException(String message, Throwable cause) {
		super(message, cause);
	}
}
