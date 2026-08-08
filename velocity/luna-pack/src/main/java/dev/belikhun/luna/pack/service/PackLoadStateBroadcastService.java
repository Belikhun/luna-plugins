package dev.belikhun.luna.pack.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.pack.messaging.PackLoadStateChannel;
import dev.belikhun.luna.pack.messaging.PackLoadStateMessage;

import java.util.Collection;

public final class PackLoadStateBroadcastService {
	private final ProxyServer server;
	private final LunaLogger logger;
	private final PluginMessageBus<Object, Object> messagingBus;

	public PackLoadStateBroadcastService(ProxyServer server, LunaLogger logger, PluginMessageBus<Object, Object> messagingBus) {
		this.server = server;
		this.logger = logger.scope("PackBroadcast");
		this.messagingBus = messagingBus;
		this.messagingBus.registerOutgoing(PackLoadStateChannel.CHANNEL);
	}

	public void broadcastStarted(Player player) {
		broadcast(PackLoadStateMessage.started(player.getUniqueId(), player.getUsername()));
	}

	public void broadcastCompleted(Player player) {
		broadcast(PackLoadStateMessage.completed(player.getUniqueId(), player.getUsername()));
	}

	private void broadcast(PackLoadStateMessage message) {
		Collection<RegisteredServer> targets = server.getAllServers();
		for (RegisteredServer target : targets) {
			boolean sent = messagingBus.send(target, PackLoadStateChannel.CHANNEL, message::writeTo);
			if (!sent) {
				logger.debug("Không có kết nối phù hợp để gửi trạng thái pack tới backend " + target.getServerInfo().getName() + " cho " + message.playerName());
			}
		}
		logger.debug("Đã broadcast trạng thái pack " + message.state().name() + " cho " + message.playerName() + " tới " + targets.size() + " backend.");
	}
}
