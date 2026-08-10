package dev.belikhun.luna.core.api.string;

import net.kyori.adventure.text.minimessage.MiniMessage;

/** Helpers for building MiniMessage strings that carry values from outside. */
public final class MiniText {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private MiniText() {
	}

	/**
	 * Neutralise a value so it renders as text rather than as markup.
	 *
	 * Anything a player or an operator typed goes through this before being
	 * concatenated into a message: a name or an argument containing something
	 * that looks like a tag would otherwise be parsed as one.
	 */
	public static String escape(String value) {
		return MINI_MESSAGE.escapeTags(value == null ? "" : value);
	}
}
