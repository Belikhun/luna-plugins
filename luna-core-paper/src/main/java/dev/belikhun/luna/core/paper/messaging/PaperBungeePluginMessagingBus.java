package dev.belikhun.luna.core.paper.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PaperBungeePluginMessagingBus implements PluginMessageBus<Player, Player>, PluginMessageListener {
	private static final String BUNGEE_COMPAT_CHANNEL = "BungeeCord";

	private final Plugin plugin;
	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final Messenger messenger;
	private final Map<String, PluginMessageHandler<Player>> incomingHandlers;
	private final Set<String> outgoingChannels;

	PaperBungeePluginMessagingBus(Plugin plugin, LunaLogger logger, boolean loggingEnabled) {
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging");
		this.loggingEnabled = loggingEnabled;
		this.messenger = plugin.getServer().getMessenger();
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<Player> handler) {
		String bukkitChannel = toBukkitChannel(channel);
		PluginMessageHandler<Player> previous = incomingHandlers.put(bukkitChannel, handler);
		if (previous == null) {
			messenger.registerIncomingPluginChannel(plugin, bukkitChannel, this);
		}
		if (loggingEnabled) {
			logger.debug("Đã đăng ký incoming plugin channel: " + bukkitChannel);
		}
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (incomingHandlers.remove(bukkitChannel) != null) {
			messenger.unregisterIncomingPluginChannel(plugin, bukkitChannel, this);
			if (loggingEnabled) {
				logger.debug("Đã hủy incoming plugin channel: " + bukkitChannel);
			}
		}
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (outgoingChannels.add(bukkitChannel)) {
			messenger.registerOutgoingPluginChannel(plugin, bukkitChannel);
			if (loggingEnabled) {
				logger.debug("Đã đăng ký outgoing plugin channel: " + bukkitChannel);
			}
		}
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (outgoingChannels.remove(bukkitChannel)) {
			messenger.unregisterOutgoingPluginChannel(plugin, bukkitChannel);
			if (loggingEnabled) {
				logger.debug("Đã hủy outgoing plugin channel: " + bukkitChannel);
			}
		}
	}

	@Override
	public boolean send(Player target, PluginMessageChannel channel, byte[] payload) {
		String bukkitChannel = toBukkitChannel(channel);
		if (!outgoingChannels.contains(bukkitChannel)) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + bukkitChannel);
		}

		if (loggingEnabled) {
			logger.audit("[TX] backend->proxy channel=" + toApiChannel(bukkitChannel)
				+ " target=" + target.getName()
				+ " bytes=" + payload.length);
		}

		target.sendPluginMessage(plugin, bukkitChannel, payload);
		return true;
	}

	@Override
	public void close() {
		for (String channel : incomingHandlers.keySet()) {
			messenger.unregisterIncomingPluginChannel(plugin, channel, this);
		}

		for (String channel : outgoingChannels) {
			messenger.unregisterOutgoingPluginChannel(plugin, channel);
		}

		incomingHandlers.clear();
		outgoingChannels.clear();
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		String apiChannel = toApiChannel(channel);
		if (loggingEnabled) {
			logger.audit("[RX] proxy->backend channel=" + apiChannel
				+ " source=" + player.getName()
				+ " bytes=" + message.length);
		}

		PluginMessageHandler<Player> handler = incomingHandlers.get(channel);
		if (handler == null) {
			if (loggingEnabled) {
				logger.debug("[RX] Không có handler cho channel=" + apiChannel);
			}
			return;
		}

		handler.handle(new PluginMessageContext<>(PluginMessageChannel.of(apiChannel), player, message));
		if (loggingEnabled) {
			logger.audit("[RX] Đã xử lý channel=" + apiChannel + " source=" + player.getName());
		}
	}

	private String toBukkitChannel(PluginMessageChannel channel) {
		if (channel.value().equals("bungeecord:main")) {
			return BUNGEE_COMPAT_CHANNEL;
		}

		return channel.value();
	}

	private String toApiChannel(String channel) {
		if (BUNGEE_COMPAT_CHANNEL.equalsIgnoreCase(channel)) {
			return "bungeecord:main";
		}

		return channel;
	}
}
