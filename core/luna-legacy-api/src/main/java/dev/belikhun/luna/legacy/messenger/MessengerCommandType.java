package dev.belikhun.luna.legacy.messenger;

public enum MessengerCommandType {
	SWITCH_NETWORK,
	SWITCH_SERVER,
	SWITCH_DIRECT,
	SEND_DIRECT,
	SEND_POKE,
	SEND_CHAT,
	SEND_REPLY,
	/**
	 * A player died, carrying the death message the backend already rendered.
	 *
	 * Kept in step with the modern trunk's enum name for name: the proxy decodes
	 * whatever a backend sends by name, so a legacy backend announcing a death has
	 * to spell it exactly as the proxy expects.
	 */
	SEND_DEATH;

	public static MessengerCommandType byName(String value) {
		for (MessengerCommandType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}

		throw new IllegalArgumentException("Loại command messenger không hợp lệ: " + value);
	}
}
