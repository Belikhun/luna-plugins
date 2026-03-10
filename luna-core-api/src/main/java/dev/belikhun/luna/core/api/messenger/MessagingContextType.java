package dev.belikhun.luna.core.api.messenger;

public enum MessagingContextType {
	NETWORK,
	SERVER,
	DIRECT;

	public static MessagingContextType byName(String value) {
		for (MessagingContextType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Ngữ cảnh chat không hợp lệ: " + value);
	}
}
