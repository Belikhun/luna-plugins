package dev.belikhun.luna.core.api.messenger;

/**
 * Which of vanilla's three advancement frames a grant carried.
 *
 * The frame is what tells an announcement how loud to be: a CHALLENGE is a
 * milestone worth a different colour from a TASK nobody will remember. Sent as a
 * name rather than an ordinal so a backend on an older build cannot shift the
 * meaning of a number.
 */
public enum MessengerAdvancementFrame {
	TASK,
	GOAL,
	CHALLENGE;

	/**
	 * The frame a name refers to, falling back to {@link #TASK}.
	 *
	 * Unknown names are common rather than exceptional here: a mod is free to
	 * invent a frame, and one unrecognised frame is not a reason to drop an
	 * announcement the player earned.
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
