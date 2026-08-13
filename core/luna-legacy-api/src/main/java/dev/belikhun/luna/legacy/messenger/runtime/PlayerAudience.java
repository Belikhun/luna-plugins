package dev.belikhun.luna.legacy.messenger.runtime;

/**
 * Saying something to one player, on whatever the platform calls a player.
 *
 * Separate from {@code PlayerBridge} on purpose. That seam is what the messaging
 * bus needs to route bytes and nothing more; this is what a *feature* needs to
 * put text on screen. Merging them would force the messaging mod to implement
 * chat it never sends, which is how a narrow seam turns into a platform object
 * passed around by another name.
 *
 * Two methods because there are two kinds of text: a message luna authored and
 * wrote in MiniMessage, and a bare string that must not be parsed as markup.
 */
public interface PlayerAudience<P> {
	/** Render MiniMessage and send it. */
	void sendMini(P player, String miniMessage);

	/** Send text exactly as given, with no markup parsing. */
	void sendPlain(P player, String text);
}
