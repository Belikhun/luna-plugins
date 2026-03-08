package dev.belikhun.luna.core.api.logging;

import dev.belikhun.luna.core.api.exception.LoggingException;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LunaLogger {
	private final Logger delegate;
	private final String scope;
	private final boolean colorsEnabled;
	private final Map<String, LogLevel> customLevels;

	private LunaLogger(Logger delegate, String scope, boolean colorsEnabled, Map<String, LogLevel> customLevels) {
		this.delegate = delegate;
		this.scope = scope;
		this.colorsEnabled = colorsEnabled;
		this.customLevels = customLevels;
	}

	public static LunaLogger forPlugin(Plugin plugin, boolean colorsEnabled) {
		return new LunaLogger(plugin.getLogger(), "", colorsEnabled, new ConcurrentHashMap<>());
	}

	public static LunaLogger forLogger(Logger logger, boolean colorsEnabled) {
		return new LunaLogger(logger, "", colorsEnabled, new ConcurrentHashMap<>());
	}

	public LunaLogger scope(String childScope) {
		String normalized = childScope == null ? "" : childScope.trim();
		if (normalized.isBlank()) {
			return this;
		}

		String nextScope = scope.isBlank() ? normalized : scope + "/" + normalized;
		return new LunaLogger(delegate, nextScope, colorsEnabled, customLevels);
	}

	public LunaLogger registerLevel(LogLevel level) {
		customLevels.put(level.name(), level);
		return this;
	}

	public LogLevel level(String name) {
		if (name == null) {
			throw new LoggingException("Log level name cannot be null.");
		}

		LogLevel level = customLevels.get(name.trim().toUpperCase());
		if (level == null) {
			throw new LoggingException("Unknown custom log level: " + name);
		}
		return level;
	}

	public void trace(String message) {
		log(LogLevel.TRACE, message);
	}

	public void debug(String message) {
		log(LogLevel.DEBUG, message);
	}

	public void info(String message) {
		log(LogLevel.INFO, message);
	}

	public void success(String message) {
		log(LogLevel.SUCCESS, message);
	}

	public void audit(String message) {
		log(LogLevel.AUDIT, message);
	}

	public void warn(String message) {
		log(LogLevel.WARN, message);
	}

	public void error(String message) {
		log(LogLevel.ERROR, message);
	}

	public void error(String message, Throwable throwable) {
		log(LogLevel.ERROR, message, throwable);
	}

	public void log(String levelName, String message) {
		if (levelName == null) {
			throw new LoggingException("Log level name cannot be null.");
		}

		LogLevel level = customLevels.get(levelName.trim().toUpperCase());
		if (level == null) {
			throw new LoggingException("Unknown custom log level: " + levelName);
		}
		log(level, message);
	}

	public void log(LogLevel level, String message) {
		log(level, message, null);
	}

	public void log(LogLevel level, String message, Throwable throwable) {
		if (level == null) {
			throw new LoggingException("Log level cannot be null.");
		}
		String formatted = format(level, message == null ? "" : message);
		Level javaLevel = toJavaLevel(level);
		if (throwable == null) {
			delegate.log(javaLevel, formatted);
		} else {
			delegate.log(javaLevel, formatted, throwable);
		}
	}

	private String format(LogLevel level, String message) {
		String label = level.color().paint(level.name(), colorsEnabled);
		String scopePart = scope.isBlank() ? "" : LogColor.CYAN.paint("[" + scope + "]", colorsEnabled);
		return scopePart + " [" + label + "] " + message;
	}

	private Level toJavaLevel(LogLevel level) {
		if (level.priority() >= LogLevel.ERROR.priority()) {
			return Level.SEVERE;
		}
		if (level.priority() >= LogLevel.WARN.priority()) {
			return Level.WARNING;
		}
		if (level.priority() <= LogLevel.DEBUG.priority()) {
			return Level.FINE;
		}
		return Level.INFO;
	}
}

