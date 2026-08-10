package dev.belikhun.luna.core.mc.placeholder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;

/**
 * What other luna modules resolve placeholders through.
 *
 * Values come in two shapes because their callers do. A tab list rebuilds every
 * row on a timer and wants one {@link #snapshot} of everything at once; a chat
 * line wants a single identifier resolved as it is written. Both read the same
 * shared server statistics, sampled once per refresh rather than once per
 * lookup.
 *
 * The interface lives in the always-compiled root rather than beside the
 * implementation: fabric builds its own service and the forge family builds
 * another, and this is what lets a module ask for one without knowing which.
 */
public interface PlaceholderService {
	/** Re-sample the statistics shared by every player. Call on a timer. */
	void refreshSharedSnapshot();

	/**
	 * Every value this service publishes for one player, plus any of
	 * {@code requestedIdentifiers} it can answer.
	 */
	Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers);

	/**
	 * Publish another module's placeholders through this service.
	 *
	 * Extensions are asked before the core's own providers, so a module may take
	 * over an identifier the core would otherwise answer. Registering the same
	 * namespace twice is last-one-wins; there is one owner per namespace.
	 */
	void registerExtension(LunaPlaceholderExtension extension);

	/**
	 * One identifier, with or without its surrounding percent signs.
	 *
	 * @return the value, or null when nothing claims the identifier - which the
	 *         caller must leave as it found it rather than blanking
	 */
	String resolvePlaceholder(ServerPlayer player, String identifier);
}
