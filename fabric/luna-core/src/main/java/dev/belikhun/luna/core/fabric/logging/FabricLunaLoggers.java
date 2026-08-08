package dev.belikhun.luna.core.fabric.logging;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.slf4j.LoggerFactory;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A {@link LunaLogger} that writes through the loader's SLF4J binding, so luna's
 * lines land in the server log next to everything else rather than on stdout.
 *
 * Each message carries its logger's name, because fabric's log layout does not:
 * a line reads "[13:37:00] [Server thread/INFO]: message", with no field naming
 * the mod that wrote it. Bukkit prefixes the message for a plugin and NeoForge
 * puts the logger in the layout, so this is the only platform where a mod that
 * wants to be identifiable in its own server log has to say so itself - and
 * luna's own log reader is one of the things reading it.
 */
public final class FabricLunaLoggers {
	private FabricLunaLoggers() {
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
			super(loggerName == null || loggerName.isBlank() ? "LunaFabric" : loggerName.trim(), null);
			this.delegate = LoggerFactory.getLogger(getName());
			setUseParentHandlers(false);
		}

		@Override
		public void log(LogRecord record) {
			if (record == null) {
				return;
			}

			String message = "[" + getName() + "] " + (record.getMessage() == null ? "" : record.getMessage());
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
