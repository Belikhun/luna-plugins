package dev.belikhun.luna.core.paper.messaging;

import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.function.Supplier;

public final class PaperPluginMessagingBus implements PluginMessageBus<Player, Player> {
	private final PaperBungeePluginMessagingBus fallbackBus;
	private final PaperAmqpMessagingTransport amqpTransport;

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger) {
		this(plugin, logger, false);
	}

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger, boolean loggingEnabled) {
		this(plugin, logger, loggingEnabled, () -> {
			String host = plugin.getServer().getIp();
			if (host == null || host.isBlank()) {
				host = "127.0.0.1";
			}
			return new BackendMetadata(host + ":" + plugin.getServer().getPort(), "", "").sanitize();
		});
	}

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger, boolean loggingEnabled, Supplier<BackendMetadata> localBackendMetadataSupplier) {
		this.fallbackBus = new PaperBungeePluginMessagingBus(plugin, logger, loggingEnabled);
		this.amqpTransport = new PaperAmqpMessagingTransport(plugin, logger, loggingEnabled, localBackendMetadataSupplier);
	}

	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config);
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Player> handler) {
		fallbackBus.registerIncoming(channel, handler);
		amqpTransport.registerIncoming(channel, handler);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		fallbackBus.unregisterIncoming(channel);
		amqpTransport.unregisterIncoming(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		fallbackBus.registerOutgoing(channel);
		amqpTransport.registerOutgoing(channel);
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		fallbackBus.unregisterOutgoing(channel);
		amqpTransport.unregisterOutgoing(channel);
	}

	@Override
	public boolean send(Player target, PluginMessageChannel channel, byte[] payload) {
		if (amqpTransport.send(target, channel, payload)) {
			return true;
		}
		return fallbackBus.send(target, channel, payload);
	}

	@Override
	public void close() {
		amqpTransport.close();
		fallbackBus.close();
	}
}
