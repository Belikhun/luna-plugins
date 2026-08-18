package dev.belikhun.luna.core.api.messenger;

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
	 * The text travels resolved for the same reason an advancement's would: only
	 * the backend can produce it. Vanilla builds the sentence from the damage
	 * source and the killer's display name, a mod adds its own, and none of those
	 * keys exist on the proxy. It announces, it never asks anything back, so it is
	 * one of the command types nothing replies to.
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
