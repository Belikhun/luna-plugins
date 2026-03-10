package dev.belikhun.luna.core.api.messenger;

public enum MessengerPresenceType {
	FIRST_JOIN,
	JOIN,
	LEAVE,
	SWAP;

	public static MessengerPresenceType byName(String value) {
		if (value != null && value.equalsIgnoreCase("SERVER_SWITCH")) {
			return SWAP;
		}

		for (MessengerPresenceType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Loại presence messenger không hợp lệ: " + value);
	}
}
