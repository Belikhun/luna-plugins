package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;

public final class MessengerPresenceListener {
	private final VelocityMessengerRouter router;

	public MessengerPresenceListener(VelocityMessengerRouter router) {
		this.router = router;
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		router.handlePlayerJoin(event.getPlayer());
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		router.handlePlayerLeave(event.getPlayer());
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		if (event.getPreviousServer() == null) {
			return;
		}
		router.handleServerSwitch(event.getPlayer(), event.getPreviousServer().getServerInfo().getName());
	}
}
