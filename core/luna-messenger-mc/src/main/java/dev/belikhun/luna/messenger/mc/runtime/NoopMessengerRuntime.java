package dev.belikhun.luna.messenger.mc.runtime;

import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What runs when there is no plugin-message bus to reach the proxy through. */
final class NoopMessengerRuntime implements MessengerRuntime {
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
	public Optional<MessengerResult> latestResult(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void close() {
	}
}
