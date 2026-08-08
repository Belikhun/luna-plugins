package dev.belikhun.luna.core.fabric.compat;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.function.Supplier;

/**
 * Calls into Minecraft that a future game version is allowed to take away.
 *
 * This jar is compiled against one version and runs on many, so a method that
 * was renamed or removed surfaces as a {@link LinkageError} at the call site
 * rather than a compile error. Wrapping such a call means the feature behind it
 * degrades on its own: a stat falls back to its default, an optional decoration
 * is skipped, and the rest of the mod carries on. Only use this where the
 * fallback is genuinely acceptable - never to paper over a real failure.
 */
public final class Guarded {
	private Guarded() {
	}

	/** Result of the call, or the fallback when this game version lacks it. */
	public static <T> T value(Supplier<T> call, T fallback) {
		try {
			T result = call.get();
			return result == null ? fallback : result;
		} catch (Throwable ignored) {
			return fallback;
		}
	}

	public static int intValue(IntCall call, int fallback) {
		try {
			return call.get();
		} catch (Throwable ignored) {
			return fallback;
		}
	}

	public static boolean booleanValue(BooleanCall call, boolean fallback) {
		try {
			return call.get();
		} catch (Throwable ignored) {
			return fallback;
		}
	}

	/**
	 * Run a side effect, reporting once at debug level if the game no longer
	 * offers it. Used for registrations whose absence costs a feature, not the
	 * mod.
	 */
	public static boolean run(Runnable call, String what, LunaLogger logger) {
		try {
			call.run();
			return true;
		} catch (Throwable throwable) {
			if (logger != null) {
				logger.warn("Bỏ qua " + what + " vì phiên bản Minecraft này không hỗ trợ: " + throwable);
			}
			return false;
		}
	}

	@FunctionalInterface
	public interface IntCall {
		int get();
	}

	@FunctionalInterface
	public interface BooleanCall {
		boolean get();
	}
}
