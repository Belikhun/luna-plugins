package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MessengerPresenceListener {
	private final VelocityMessengerRouter router;
	private final Map<UUID, String> lastKnownServerByPlayer;

	public MessengerPresenceListener(VelocityMessengerRouter router) {
		this.router = router;
		this.lastKnownServerByPlayer = new ConcurrentHashMap<>();
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		String serverName = lastKnownServerByPlayer.remove(event.getPlayer().getUniqueId());
		router.handlePlayerLeave(event.getPlayer(), serverName);
	}

	@Subscribe
	public void onServerConnected(ServerConnectedEvent event) {
		// Only update tracking state here. Actual join/switch handling is
		// deferred to onServerPostConnect so that plugin messages are not
		// sent through a connection whose play-state transition is still
		// in progress — doing so can corrupt the packet stream and cause
		// DecoderException on the backend (e.g. oversized accept_teleportation).
		String currentServerName = event.getServer().getServerInfo().getName();
		lastKnownServerByPlayer.put(event.getPlayer().getUniqueId(), currentServerName);
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		var player = event.getPlayer();
		String currentServerName = player.getCurrentServer()
			.map(conn -> conn.getServerInfo().getName())
			.orElse(lastKnownServerByPlayer.getOrDefault(player.getUniqueId(), ""));

		if (event.getPreviousServer() == null) {
			router.handlePlayerJoin(player, currentServerName);
		} else {
			String previousServerName = event.getPreviousServer().getServerInfo().getName();
			router.handleServerSwitch(player, previousServerName, currentServerName);
		}

		router.flushPendingSelfPresence(player);
	}
}
