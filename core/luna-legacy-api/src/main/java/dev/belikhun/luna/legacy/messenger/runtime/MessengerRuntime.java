package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.messenger.MessengerCommandType;

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
public interface MessengerRuntime<P> extends AutoCloseable {
	void publishJoin(P player, boolean firstJoin);

	void publishLeave(P player);

	boolean sendCommand(P player, MessengerCommandType commandType, String argument);

	boolean sendCommand(P player, MessengerCommandType commandType, String argument, String targetName);

	Collection<String> suggestDirectTargets(String partial, String senderName);

	Optional<MessengerResult> latestResult(UUID playerId);

	@Override
	void close();
}
