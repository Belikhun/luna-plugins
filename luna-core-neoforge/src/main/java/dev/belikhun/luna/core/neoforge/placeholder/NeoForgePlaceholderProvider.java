package dev.belikhun.luna.core.neoforge.placeholder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

interface NeoForgePlaceholderProvider {
	default void contributeSnapshot(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		NeoForgePlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
	}

	default String resolveLunaValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawKey,
		String normalizedKey,
		NeoForgePlaceholderSnapshot snapshot
	) {
		return null;
	}

	default String resolveNativeValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawIdentifier,
		String normalizedIdentifier,
		NeoForgePlaceholderSnapshot snapshot
	) {
		return null;
	}
}
