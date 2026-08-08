package dev.belikhun.luna.core.paper.heartbeat;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PaperHeartbeatPlayerListener implements Listener {
	private final PaperHeartbeatPublisher heartbeatPublisher;

	public PaperHeartbeatPlayerListener(PaperHeartbeatPublisher heartbeatPublisher) {
		this.heartbeatPublisher = heartbeatPublisher;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		heartbeatPublisher.publishNowIfPlayerCountChanged();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		heartbeatPublisher.publishNowIfPlayerCountChanged();
	}
}
