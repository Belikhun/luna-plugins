package dev.belikhun.luna.messenger.neoforge;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class NoopNeoForgeMessengerRuntime implements NeoForgeMessengerRuntime {
	private final BackendPlaceholderResolver placeholderResolver;

	NoopNeoForgeMessengerRuntime(BackendPlaceholderResolver placeholderResolver) {
		this.placeholderResolver = placeholderResolver;
	}

	@Override
	public void publishJoin(ServerPlayer player, boolean firstJoin) {
	}

	@Override
	public void publishLeave(ServerPlayer player) {
	}

	@Override
	public boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument) {
		return false;
	}

	@Override
	public boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument, String targetName) {
		return false;
	}

	@Override
	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		return List.of();
	}

	@Override
	public Optional<NeoForgeMessengerResult> latestResult(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public BackendPlaceholderResolver placeholderResolver() {
		return placeholderResolver;
	}

	@Override
	public void close() {
	}
}
