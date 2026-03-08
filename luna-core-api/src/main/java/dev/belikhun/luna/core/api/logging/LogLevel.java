package dev.belikhun.luna.core.api.logging;

import dev.belikhun.luna.core.api.exception.LoggingException;

import java.util.Locale;

public record LogLevel(String name, int priority, LogColor color) {
	public static final LogLevel TRACE = new LogLevel("TRACE", 100, LogColor.GRAY);
	public static final LogLevel DEBUG = new LogLevel("DEBUG", 200, LogColor.CYAN);
	public static final LogLevel INFO = new LogLevel("INFO", 300, LogColor.BLUE);
	public static final LogLevel SUCCESS = new LogLevel("SUCCESS", 350, LogColor.BRIGHT_GREEN);
	public static final LogLevel AUDIT = new LogLevel("AUDIT", 375, LogColor.MAGENTA);
	public static final LogLevel WARN = new LogLevel("WARN", 400, LogColor.BRIGHT_YELLOW);
	public static final LogLevel ERROR = new LogLevel("ERROR", 500, LogColor.BRIGHT_RED);

	public LogLevel {
		if (name == null) {
			throw new LoggingException("Log level name cannot be null.");
		}
		if (color == null) {
			throw new LoggingException("Log level color cannot be null.");
		}
		name = name.trim().toUpperCase(Locale.ROOT);
		if (name.isBlank()) {
			throw new LoggingException("Log level name cannot be blank.");
		}
	}

	public static LogLevel custom(String name, int priority, LogColor color) {
		return new LogLevel(name, priority, color);
	}
}

