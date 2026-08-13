package dev.belikhun.luna.legacy.logging;

/**
 * What luna's code logs through.
 *
 * An interface here, where the modern api has a concrete class over
 * `java.util.logging`. 1.12.2 Forge hands a mod a log4j `Logger` and nothing else, so
 * the sink has to come from the platform; the shape of a line does not.
 *
 * `scope` is the part worth keeping faithful. Every luna module logs under a path -
 * `[Core/Registry]`, `[Core/PluginMessaging/AMQP]` - and that path is read by humans
 * across four platforms' logs side by side. A scope returns a new logger rather than
 * mutating this one, so a subsystem can hold its own without affecting its parent.
 */
public interface LunaLogger {
	void trace(String message);

	void debug(String message);

	void info(String message);

	void success(String message);

	void audit(String message);

	void warn(String message);

	void error(String message);

	void error(String message, Throwable cause);

	/** A logger writing under `this` scope plus `childScope`. */
	LunaLogger scope(String childScope);
}
