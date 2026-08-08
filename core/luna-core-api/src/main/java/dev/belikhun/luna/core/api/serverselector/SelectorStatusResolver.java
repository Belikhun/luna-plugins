package dev.belikhun.luna.core.api.serverselector;

import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;

/**
 * The one place that decides which of the four selector states a backend is in.
 *
 * The proxy applies it when a player asks to connect and the backend applies it
 * when it draws the menu; those two answers disagreeing is a bug the player sees
 * as an item they cannot click, so both go through this.
 */
public final class SelectorStatusResolver {
	public static final String ONLINE = "ONLINE";
	public static final String OFFLINE = "OFFLINE";
	public static final String MAINT = "MAINT";
	public static final String NOP = "NOP";

	private SelectorStatusResolver() {
	}

	/**
	 * @param status       the registry row, or null when the name resolved to nothing
	 * @param noPermission whether the viewer is barred from this backend
	 * @return one of ONLINE / OFFLINE / MAINT / NOP
	 */
	public static String resolve(BackendServerStatus status, boolean noPermission) {
		if (noPermission) {
			return NOP;
		}

		if (status == null || !status.online()) {
			return OFFLINE;
		}

		if (status.stats() != null && status.stats().whitelistEnabled()) {
			return MAINT;
		}

		return ONLINE;
	}
}
