package dev.belikhun.luna.legacy.permission;

/**
 * Set, unset, or not known yet.
 *
 * A boolean cannot carry this and the difference is not academic: luna has
 * permissions whose absence means *allowed* (Bukkit's `PermissionDefault.TRUE`), so a
 * mirror that answered false for "I have not fetched this player yet" would lock
 * every player out of them the moment the proxy was slow. Every lookup on the mirror
 * returns one of these, and the caller says what undefined means for its own node.
 */
public enum Tristate {
	TRUE,
	FALSE,
	UNDEFINED;

	public static Tristate of(boolean value) {
		return value ? TRUE : FALSE;
	}

	public static Tristate of(Boolean value) {
		if (value == null) {
			return UNDEFINED;
		}

		return of(value.booleanValue());
	}

	/** What this means, given what an unset node means to the caller. */
	public boolean orElse(boolean fallback) {
		if (this == UNDEFINED) {
			return fallback;
		}

		return this == TRUE;
	}

	public boolean defined() {
		return this != UNDEFINED;
	}
}
