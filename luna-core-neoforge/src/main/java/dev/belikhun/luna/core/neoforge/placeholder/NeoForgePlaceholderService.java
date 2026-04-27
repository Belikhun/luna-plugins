package dev.belikhun.luna.core.neoforge.placeholder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Map;

public interface NeoForgePlaceholderService {
	void refreshSharedSnapshot();

	Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers);

	String resolvePlaceholder(ServerPlayer player, String identifier);
}
