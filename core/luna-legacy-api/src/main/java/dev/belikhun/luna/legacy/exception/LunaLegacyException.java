package dev.belikhun.luna.legacy.exception;

/** Base of every exception this module throws, mirroring `LunaCoreException`. */
public class LunaLegacyException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public LunaLegacyException(String message) {
		super(message);
	}

	public LunaLegacyException(String message, Throwable cause) {
		super(message, cause);
	}
}
