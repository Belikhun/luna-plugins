package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public interface TabBridgeRelationalPlaceholderSource {
	Map<String, Map<String, String>> resolve(ServerPlayer viewer);
}