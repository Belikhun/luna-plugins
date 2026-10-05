package dev.belikhun.luna.core.mc.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;

/**
 * What other luna modules resolve placeholders through on a mod loader.
 *
 * A thin face over the shared {@link PlaceholderRuntime}: a tab list asks for a
 * {@link #snapshot} of the identifiers it was told to watch, a chat line asks
 * for one identifier. Modules that publish placeholders go to the runtime
 * itself and register specs.
 */
public interface PlaceholderService {
	/** The runtime behind this service, for registering specs and for inspection. */
	PlaceholderRuntime<ServerPlayer> runtime();

	/**
	 * Makes sure a server sample exists. Sampling itself runs on the core's own
	 * tick cadence, so calling this often costs nothing.
	 */
	void refreshSharedSnapshot();

	/**
	 * The values of the requested identifiers for one player, keyed by the
	 * identifier as the caller wrote it. Identifiers nothing claims are absent.
	 */
	Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers);

	/**
	 * One identifier, with or without its surrounding percent signs.
	 *
	 * @return the value, or null when nothing claims the identifier, which the
	 *         caller must leave as it found it rather than blanking
	 */
	String resolvePlaceholder(ServerPlayer player, String identifier);
}
