package dev.belikhun.luna.core.mc12.logging;

import dev.belikhun.luna.legacy.logging.LogColor;
import dev.belikhun.luna.legacy.logging.LogLevel;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;
import org.apache.logging.log4j.Logger;

/**
 * `LunaLogger` over the log4j logger FML hands a mod.
 *
 * The line shape is the one every other platform prints - `[Name] [Scope] [LEVEL] msg`
 * - and that is not decoration: luna's log attribution reads a 1.12.2 line back by
 * matching the bracketed name, so dropping the tag makes a backend's lines
 * unattributable in the console's plugin report.
 *
 * Colours are off by default here. Forge 1.12.2 writes latest.log through log4j with
 * no ANSI filtering, so painting would leave escape sequences in the archived file
 * that luna later reads.
 */
public final class LegacyLunaLogger implements LunaLogger {
	private final Logger logger;
	private final String name;
	private final String scope;
	private final boolean colorsEnabled;
	private final boolean debugEnabled;

	private LegacyLunaLogger(Logger logger, String name, String scope, boolean colorsEnabled, boolean debugEnabled) {
		this.logger = logger;
		this.name = name;
		this.scope = scope;
		this.colorsEnabled = colorsEnabled;
		this.debugEnabled = debugEnabled;
	}

	public static LegacyLunaLogger create(Logger logger, String name) {
		return new LegacyLunaLogger(logger, name, "", false, false);
	}

	public LegacyLunaLogger withDebug(boolean enabled) {
		return new LegacyLunaLogger(logger, name, scope, colorsEnabled, enabled);
	}

	@Override
	public LunaLogger scope(String childScope) {
		String normalized = Strings.trimmed(childScope);

		if (Strings.isBlank(normalized)) {
			return this;
		}

		String nextScope = Strings.isBlank(scope) ? normalized : scope + "/" + normalized;

		return new LegacyLunaLogger(logger, name, nextScope, colorsEnabled, debugEnabled);
	}

	@Override
	public void trace(String message) {
		if (debugEnabled) {
			log(LogLevel.TRACE, message, null);
		}
	}

	@Override
	public void debug(String message) {
		if (debugEnabled) {
			log(LogLevel.DEBUG, message, null);
		}
	}

	@Override
	public void info(String message) {
		log(LogLevel.INFO, message, null);
	}

	@Override
	public void success(String message) {
		log(LogLevel.SUCCESS, message, null);
	}

	@Override
	public void audit(String message) {
		log(LogLevel.AUDIT, message, null);
	}

	@Override
	public void warn(String message) {
		log(LogLevel.WARN, message, null);
	}

	@Override
	public void error(String message) {
		log(LogLevel.ERROR, message, null);
	}

	@Override
	public void error(String message, Throwable cause) {
		log(LogLevel.ERROR, message, cause);
	}

	private void log(LogLevel level, String message, Throwable cause) {
		String line = format(level, message == null ? "" : message);

		if (level.priority() >= LogLevel.ERROR.priority()) {
			logError(line, cause);

			return;
		}

		if (level.priority() >= LogLevel.WARN.priority()) {
			logger.warn(line);

			return;
		}

		logger.info(line);
	}

	private void logError(String line, Throwable cause) {
		if (cause == null) {
			logger.error(line);

			return;
		}

		logger.error(line, cause);
	}

	private String format(LogLevel level, String message) {
		String label = level.color().paint(level.name(), colorsEnabled);
		String scopePart = Strings.isBlank(scope)
			? ""
			: LogColor.CYAN.paint("[" + scope + "]", colorsEnabled) + " ";

		return "[" + name + "] " + scopePart + "[" + label + "] " + message;
	}
}
