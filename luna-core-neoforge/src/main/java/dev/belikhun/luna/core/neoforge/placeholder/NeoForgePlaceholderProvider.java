package dev.belikhun.luna.core.neoforge.placeholder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

interface NeoForgePlaceholderProvider extends NeoForgePlaceholderNamespaceProvider {
	default void contributeSnapshot(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		NeoForgePlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
	}

	default String resolve(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		NeoForgePlaceholderSnapshot snapshot
	) {
		return null;
	}
}
