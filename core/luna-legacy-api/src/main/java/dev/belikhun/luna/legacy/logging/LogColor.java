package dev.belikhun.luna.legacy.logging;

/**
 * The ANSI palette luna's log lines are painted with.
 *
 * The modern api writes the escape as a `\\u001B` literal. Here it is built from its
 * code point instead: a unicode escape is processed before the lexer sees the source,
 * so a stray one is a compile error rather than a bad string, and this file is copied
 * around enough that spelling it out is worth the one extra constant.
 */
public enum LogColor {
	RESET(0),
	BLACK(30),
	RED(31),
	GREEN(32),
	YELLOW(33),
	BLUE(34),
	MAGENTA(35),
	CYAN(36),
	WHITE(37),
	GRAY(90),
	BRIGHT_RED(91),
	BRIGHT_GREEN(92),
	BRIGHT_YELLOW(93),
	BRIGHT_BLUE(94),
	BRIGHT_MAGENTA(95),
	BRIGHT_CYAN(96),
	BOLD(1);

	private static final char ESCAPE = (char) 27;

	private final String ansi;

	LogColor(int code) {
		this.ansi = ESCAPE + "[" + code + "m";
	}

	public String ansi() {
		return ansi;
	}

	public String paint(String message, boolean enabled) {
		if (!enabled) {
			return message;
		}

		return ansi + message + RESET.ansi;
	}
}
