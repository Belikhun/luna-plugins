package dev.belikhun.luna.core.fabric.lifecycle;

import org.slf4j.LoggerFactory;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class FabricJulLogger extends Logger {
	private final org.slf4j.Logger delegate;

	private FabricJulLogger(String name) {
		super(name, null);
		this.delegate = LoggerFactory.getLogger(name);
		setUseParentHandlers(false);
	}

	public static Logger create(String name) {
		return new FabricJulLogger(name);
	}

	@Override
	public void log(LogRecord record) {
		if (record == null) {
			return;
		}

		String message = record.getMessage() == null ? "" : record.getMessage();
		Throwable throwable = record.getThrown();
		Level level = record.getLevel();
		if (level == null) {
			delegate.info(message, throwable);
			return;
		}

		if (level.intValue() >= Level.SEVERE.intValue()) {
			delegate.error(message, throwable);
			return;
		}
		if (level.intValue() >= Level.WARNING.intValue()) {
			delegate.warn(message, throwable);
			return;
		}
		if (level.intValue() <= Level.FINE.intValue()) {
			delegate.debug(message, throwable);
			return;
		}

		delegate.info(message, throwable);
	}
}
