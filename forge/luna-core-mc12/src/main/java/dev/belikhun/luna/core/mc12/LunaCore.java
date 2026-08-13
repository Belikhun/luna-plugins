package dev.belikhun.luna.core.mc12;

import dev.belikhun.luna.legacy.dependency.DependencyManager;

/**
 * How a luna feature mod on 1.12.2 reaches the core.
 *
 * Deliberately a static holder, matching the modern trunk's `LunaCore`. Legacy
 * FML has no service registry and no injection, and a feature mod that wanted
 * the core's objects would otherwise have to reach into the core's `@Mod`
 * instance reflectively - which is worse in every way than one published field.
 *
 * **Load order is the thing to respect.** Every luna mod is a peer on the same
 * bus; nothing guarantees the core's `FMLServerStartingEvent` handler runs before
 * a feature's. So a feature asks {@link #isReady()} and degrades rather than
 * assuming, exactly as the forge and fabric builds already do.
 */
public final class LunaCore {
	private static volatile DependencyManager services;

	private LunaCore() {
	}

	/** Published services, or null when the core has not started yet. */
	public static DependencyManager services() {
		return services;
	}

	/** Whether {@link #services()} would answer with anything. */
	public static boolean isReady() {
		return services != null;
	}

	/**
	 * Find a service, or null - the whole api a feature mod needs.
	 *
	 * Answers null both when the core has not started and when it started without
	 * that service, because a feature has to handle both the same way: carry on
	 * without it.
	 */
	public static <T> T find(Class<T> type) {
		DependencyManager current = services;

		return current == null ? null : current.find(type);
	}

	/** Called by the core's own bootstrap; not part of the feature-facing api. */
	public static void set(DependencyManager registry) {
		services = registry;
	}

	public static void clear() {
		services = null;
	}
}
