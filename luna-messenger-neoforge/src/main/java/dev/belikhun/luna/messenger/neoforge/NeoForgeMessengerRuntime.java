package dev.belikhun.luna.messenger.neoforge;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface NeoForgeMessengerRuntime extends AutoCloseable {
	void publishJoin(ServerPlayer player, boolean firstJoin);

	void publishLeave(ServerPlayer player);

	boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument);

	boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument, String targetName);

	Collection<String> suggestDirectTargets(String partial, String senderName);

	Optional<NeoForgeMessengerResult> latestResult(UUID playerId);

	BackendPlaceholderResolver placeholderResolver();

	@Override
	void close();
}
