package dev.belikhun.luna.core.api.messenger;

public enum MessengerResultType {
	INFO,
	ERROR,
	MENTION_ALERT,
	NETWORK_CHAT,
	SERVER_CHAT,
	DIRECT_CHAT,
	DIRECT_ECHO;

	public static MessengerResultType byName(String value) {
		for (MessengerResultType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Loại kết quả messenger không hợp lệ: " + value);
	}
}
