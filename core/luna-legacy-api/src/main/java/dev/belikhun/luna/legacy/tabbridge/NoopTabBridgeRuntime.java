package dev.belikhun.luna.legacy.tabbridge;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The runtime a backend gets when it has no way to carry TAB's channel.
 *
 * It answers nothing rather than failing to start: the tab list then renders
 * from what the proxy already knows, which is a worse tab list and a running
 * server.
 */
final class NoopTabBridgeRuntime<P> implements TabBridgeRuntime<P> {
	@Override
	public boolean sendRaw(P player, byte[] payload) {
		return false;
	}

	@Override
	public void bindPlaceholderResolver(TabBridgePlaceholderResolver<P> placeholderResolver) {
	}

	@Override
	public void updatePlayerPlaceholders(P player, Map<String, String> placeholderValues) {
	}

	@Override
	public void updatePlayerRelationalPlaceholders(P player, Map<String, Map<String, String>> placeholderValues) {
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		return Collections.emptySet();
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		return Collections.emptyMap();
	}

	@Override
	public TabBridgePacket latestPacket(UUID playerId) {
		return null;
	}

	@Override
	public void removePlayer(UUID playerId) {
	}

	@Override
	public void close() {
	}
}
