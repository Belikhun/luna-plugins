package dev.belikhun.luna.tv;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * Tracing for the parts of this plugin that are hard to see from a chair.
 *
 * A screen has three places it can silently do nothing - the browser not
 * painting, the frame not being pushed, the click not landing - and they all
 * look identical in game: empty maps. This makes each of them say so.
 *
 * Written as a static holder because the call sites are on four different
 * threads and none of them should have to carry a logger for the privilege of
 * being debuggable. Off by default; {@code /lunatv debug on} flips it live.
 */
public final class TvDebug {

	private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

	private static volatile LunaLogger logger;
	private static volatile boolean enabled;

	private TvDebug() {
	}

	/**
	 * Binds the logger and the initial state.
	 *
	 * @param target logger to write through
	 * @param on whether tracing starts enabled
	 */
	public static void init(LunaLogger target, boolean on) {
		logger = target;
		enabled = on;
	}

	public static void enabled(boolean on) {
		enabled = on;
		COUNTERS.clear();
	}

	public static boolean enabled() {
		return enabled;
	}

	/**
	 * Logs a line when tracing is on.
	 *
	 * @param message what happened
	 */
	public static void log(String message) {
		LunaLogger target = logger;

		if (!enabled || target == null) {
			return;
		}

		target.info("[trace] " + message);
	}

	/**
	 * Logs a line from a hot path, thinned out so it stays readable.
	 *
	 * The first few occurrences are always shown, because the interesting part of
	 * a broken stream is usually its beginning, and after that one in every
	 * {@code every} is kept.
	 *
	 * @param key groups occurrences, e.g. "push:bigtv"
	 * @param every keep one line in this many after the first few
	 * @param message what happened
	 */
	public static void sampled(String key, int every, String message) {
		if (!enabled) {
			return;
		}

		long count = COUNTERS.computeIfAbsent(key, unused -> new AtomicLong()).incrementAndGet();

		if (count > 3 && count % every != 0) {
			return;
		}

		log(message + " [#" + count + "]");
	}
}
