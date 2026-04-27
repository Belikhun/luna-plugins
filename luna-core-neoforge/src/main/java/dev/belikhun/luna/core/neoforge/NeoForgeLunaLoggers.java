package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.slf4j.LoggerFactory;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class NeoForgeLunaLoggers {
	private NeoForgeLunaLoggers() {
	}

	public static LunaLogger create(String loggerName, boolean colorsEnabled) {
		return create(loggerName, colorsEnabled, false);
	}

	public static LunaLogger create(String loggerName, boolean colorsEnabled, boolean debugEnabled) {
		LunaLogger logger = LunaLogger.forLogger(new Slf4jForwardingLogger(loggerName), colorsEnabled);
		return debugEnabled ? logger.withDebug(true) : logger;
	}

	private static final class Slf4jForwardingLogger extends Logger {
		private final org.slf4j.Logger delegate;

		private Slf4jForwardingLogger(String loggerName) {
			super(loggerName == null || loggerName.isBlank() ? "LunaNeoForge" : loggerName.trim(), null);
			this.delegate = LoggerFactory.getLogger(getName());
			setUseParentHandlers(false);
		}

		@Override
		public void log(LogRecord record) {
			if (record == null) {
				return;
			}

			String message = record.getMessage() == null ? "" : record.getMessage();
			Throwable throwable = record.getThrown();
			Level level = record.getLevel();
			if (level != null && level.intValue() >= Level.SEVERE.intValue()) {
				if (throwable == null) {
					delegate.error(message);
				} else {
					delegate.error(message, throwable);
				}
				return;
			}

			if (level != null && level.intValue() >= Level.WARNING.intValue()) {
				if (throwable == null) {
					delegate.warn(message);
				} else {
					delegate.warn(message, throwable);
				}
				return;
			}

			if (level != null && level.intValue() <= Level.FINE.intValue()) {
				if (throwable == null) {
					delegate.debug(message);
				} else {
					delegate.debug(message, throwable);
				}
				return;
			}

			if (throwable == null) {
				delegate.info(message);
			} else {
				delegate.info(message, throwable);
			}
		}
	}
}
