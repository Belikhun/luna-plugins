package dev.belikhun.luna.core.api.placeholder;

/** Whose value a placeholder is. */
public enum PlaceholderScope {
	/** The same for everyone: TPS, online count, a world's weather. */
	SERVER,
	/** Depends on the viewer: ping, level, balance. */
	PLAYER,
	/** Depends on the viewer and a target, TAB's {@code %rel_*%}. */
	RELATIONAL
}
