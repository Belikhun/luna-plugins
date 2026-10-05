package dev.belikhun.luna.core.api.placeholder;

/**
 * What a key parameter accepts.
 *
 * A parameter binds from the tokens after the key's literal words, or from a
 * MiniMessage tag's explicit arguments once the tokens run out.
 */
public enum ParamType {
	/** One token that parses as an integer. */
	INT,
	/** One token that parses as an integer and is clamped to the bar width range. */
	WIDTH,
	/** One token, any text without an underscore. */
	WORD,
	/** One or more tokens up to the next literal word, joined back with underscores. */
	TEXT,
	/** Everything left, joined back with underscores; must be the last parameter. */
	REST;

	/** Whether a value is acceptable for this type. */
	public boolean accepts(String value) {
		if (value == null) {
			return false;
		}

		if (this == INT || this == WIDTH) {
			try {
				Integer.parseInt(value.trim());
				return true;
			} catch (NumberFormatException exception) {
				return false;
			}
		}

		return !value.isEmpty();
	}
}
