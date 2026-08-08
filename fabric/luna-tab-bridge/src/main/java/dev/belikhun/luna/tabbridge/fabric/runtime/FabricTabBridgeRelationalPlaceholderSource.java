package dev.belikhun.luna.tabbridge.fabric.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public interface FabricTabBridgeRelationalPlaceholderSource {
	Map<String, Map<String, String>> resolve(ServerPlayer viewer);
}