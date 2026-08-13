package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.messenger.MessengerCommandType;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What runs when there is no plugin-message bus to reach the proxy through. */
final class NoopMessengerRuntime<P> implements MessengerRuntime<P> {
	@Override
	public void publishJoin(P player, boolean firstJoin) {
	}

	@Override
	public void publishLeave(P player) {
	}

	@Override
	public boolean sendCommand(P player, MessengerCommandType commandType, String argument) {
		return false;
	}

	@Override
	public boolean sendCommand(P player, MessengerCommandType commandType, String argument, String targetName) {
		return false;
	}

	@Override
	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		return Collections.<String>emptyList();
	}

	@Override
	public Optional<MessengerResult> latestResult(UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void close() {
	}
}
