package dev.belikhun.luna.legacy.tabbridge;

import java.util.Map;

/**
 * Placeholders whose value depends on who is looking.
 *
 * TAB calls these relational: `%rel_luna_player_prefix%` is not one value but
 * one per pair of players, because a viewer may be shown a different prefix for
 * the same target. The map is therefore identifier to target-name to value.
 */
public interface TabBridgeRelationalPlaceholderSource<P> {
	Map<String, Map<String, String>> resolve(P viewer);
}
