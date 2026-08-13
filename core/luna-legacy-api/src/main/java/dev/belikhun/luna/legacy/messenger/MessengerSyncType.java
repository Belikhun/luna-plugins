package dev.belikhun.luna.legacy.messenger;

public enum MessengerSyncType {
	CONTEXT_REQUEST,
	CONTEXT_UPDATE,
	CONTEXT_SNAPSHOT;

	public static MessengerSyncType byName(String value) {
		for (MessengerSyncType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Loại đồng bộ messenger không hợp lệ: " + value);
	}
}
