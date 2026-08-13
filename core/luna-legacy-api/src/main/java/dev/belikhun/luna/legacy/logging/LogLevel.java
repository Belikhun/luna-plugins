package dev.belikhun.luna.legacy.logging;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.Locale;

/**
 * A named severity and the colour it prints in.
 *
 * A record in the modern api; the compact constructor's validation and normalisation
 * are kept exactly, because the level *name* is what reaches the log line and luna's
 * own log reader matches on it.
 */
public final class LogLevel {
	public static final LogLevel TRACE = new LogLevel("TRACE", 100, LogColor.GRAY);
	public static final LogLevel DEBUG = new LogLevel("DEBUG", 200, LogColor.CYAN);
	public static final LogLevel INFO = new LogLevel("INFO", 300, LogColor.BLUE);
	public static final LogLevel SUCCESS = new LogLevel("SUCCESS", 350, LogColor.BRIGHT_GREEN);
	public static final LogLevel AUDIT = new LogLevel("AUDIT", 375, LogColor.MAGENTA);
	public static final LogLevel WARN = new LogLevel("WARN", 400, LogColor.BRIGHT_YELLOW);
	public static final LogLevel ERROR = new LogLevel("ERROR", 500, LogColor.BRIGHT_RED);

	private final String name;
	private final int priority;
	private final LogColor color;

	public LogLevel(String name, int priority, LogColor color) {
		if (name == null) {
			throw new LunaLegacyException("Log level name cannot be null.");
		}

		if (color == null) {
			throw new LunaLegacyException("Log level color cannot be null.");
		}

		String normalized = name.trim().toUpperCase(Locale.ROOT);

		if (Strings.isBlank(normalized)) {
			throw new LunaLegacyException("Log level name cannot be blank.");
		}

		this.name = normalized;
		this.priority = priority;
		this.color = color;
	}

	public static LogLevel custom(String name, int priority, LogColor color) {
		return new LogLevel(name, priority, color);
	}

	public String name() {
		return name;
	}

	public int priority() {
		return priority;
	}

	public LogColor color() {
		return color;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof LogLevel)) {
			return false;
		}

		LogLevel that = (LogLevel) other;

		return priority == that.priority && name.equals(that.name) && color == that.color;
	}

	@Override
	public int hashCode() {
		int result = name.hashCode();

		result = 31 * result + priority;
		result = 31 * result + color.hashCode();

		return result;
	}

	@Override
	public String toString() {
		return "LogLevel[name=" + name + ", priority=" + priority + ", color=" + color + "]";
	}
}
