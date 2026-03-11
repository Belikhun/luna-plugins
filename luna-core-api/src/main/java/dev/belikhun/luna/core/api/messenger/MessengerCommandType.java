package dev.belikhun.luna.core.api.messenger;

public enum MessengerCommandType {
	SWITCH_NETWORK,
	SWITCH_SERVER,
	SWITCH_DIRECT,
	SEND_DIRECT,
	SEND_POKE,
	SEND_CHAT,
	SEND_REPLY;

	public static MessengerCommandType byName(String value) {
		for (MessengerCommandType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Loại command messenger không hợp lệ: " + value);
	}
}
