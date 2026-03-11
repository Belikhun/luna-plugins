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
		String currentServerName = event.getServer().getServerInfo().getName();
		lastKnownServerByPlayer.put(event.getPlayer().getUniqueId(), currentServerName);

		if (event.getPreviousServer().isEmpty()) {
			router.handlePlayerJoin(event.getPlayer(), currentServerName);
			return;
		}

		String previousServerName = event.getPreviousServer().get().getServerInfo().getName();
		router.handleServerSwitch(event.getPlayer(), previousServerName);
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		router.flushPendingSelfPresence(event.getPlayer());
	}
}
