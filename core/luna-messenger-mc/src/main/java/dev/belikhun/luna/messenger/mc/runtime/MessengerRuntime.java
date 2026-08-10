package dev.belikhun.luna.messenger.mc.runtime;

import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * What the commands and chat listener talk to.
 *
 * There are two implementations: the real one, and the one that answers "no" to
 * everything because the plugin-message bus is not up. Keeping the second is
 * what lets the commands stay registered and say so, instead of vanishing.
 */
public interface MessengerRuntime extends AutoCloseable {
	void publishJoin(ServerPlayer player, boolean firstJoin);

	void publishLeave(ServerPlayer player);

	boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument);

	boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument, String targetName);

	Collection<String> suggestDirectTargets(String partial, String senderName);

	Optional<MessengerResult> latestResult(UUID playerId);

	@Override
	void close();
}
