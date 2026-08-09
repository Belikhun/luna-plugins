package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.mc.placeholder.LunaPlaceholderExtension;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;

public interface NeoForgePlaceholderService {
	void refreshSharedSnapshot();

	Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers);

	/**
	 * Publish another module's placeholders through this service.
	 *
	 * Extensions are asked before the core's own providers, so a module may take
	 * over an identifier the core would otherwise answer. Registering the same
	 * namespace twice is last-one-wins; there is one owner per namespace.
	 */
	void registerExtension(LunaPlaceholderExtension extension);

	String resolvePlaceholder(ServerPlayer player, String identifier);
}
