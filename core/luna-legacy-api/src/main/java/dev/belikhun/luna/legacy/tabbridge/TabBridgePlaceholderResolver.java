package dev.belikhun.luna.legacy.tabbridge;

/**
 * One placeholder, resolved for one player, at the moment TAB asks for it.
 *
 * The bridge holds a snapshot of everything it was last given, but TAB may
 * register an identifier the snapshot has never carried. This is how it reaches
 * the placeholder service for that one value instead of answering blank.
 */
public interface TabBridgePlaceholderResolver<P> {
	/** @return the value, or null when nothing on this backend claims it */
	String resolve(P player, String identifier);
}
