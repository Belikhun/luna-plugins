package dev.belikhun.luna.legacy.messenger;

/**
 * Which of vanilla's three advancement frames a grant carried.
 *
 * Kept name-for-name with the modern trunk's copy: the two ends of this wire are
 * a 1.12.2 backend and a modern proxy, so the names have to agree exactly.
 */
public enum MessengerAdvancementFrame {
	TASK,
	GOAL,
	CHALLENGE;

	/**
	 * The frame a name refers to, falling back to {@link #TASK}.
	 *
	 * @param value the frame name, in any case, possibly null
	 * @return the matching frame, or TASK when there is none
	 */
	public static MessengerAdvancementFrame byName(String value) {
		if (value == null) {
			return TASK;
		}

		for (MessengerAdvancementFrame frame : values()) {
			if (frame.name().equalsIgnoreCase(value)) {
				return frame;
			}
		}

		return TASK;
	}
}
