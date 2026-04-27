package dev.belikhun.luna.tabbridge.neoforge;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public interface NeoForgeTabBridgeRelationalPlaceholderSource {
	Map<String, Map<String, String>> resolve(ServerPlayer viewer);
}