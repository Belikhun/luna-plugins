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

public final class PaperPluginMessagingBus implements PluginMessageBus<Player, Player>, PluginMessageListener {
	private static final String BUNGEE_COMPAT_CHANNEL = "BungeeCord";

	private final Plugin plugin;
	private final LunaLogger logger;
	private final Messenger messenger;
	private final Map<String, PluginMessageHandler<Player>> incomingHandlers;
	private final Set<String> outgoingChannels;

	public PaperPluginMessagingBus(Plugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger.scope("PluginMessaging");
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
		logger.debug("Đã đăng ký incoming plugin channel: " + bukkitChannel);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (incomingHandlers.remove(bukkitChannel) != null) {
			messenger.unregisterIncomingPluginChannel(plugin, bukkitChannel, this);
			logger.debug("Đã hủy incoming plugin channel: " + bukkitChannel);
		}
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (outgoingChannels.add(bukkitChannel)) {
			messenger.registerOutgoingPluginChannel(plugin, bukkitChannel);
			logger.debug("Đã đăng ký outgoing plugin channel: " + bukkitChannel);
		}
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		String bukkitChannel = toBukkitChannel(channel);
		if (outgoingChannels.remove(bukkitChannel)) {
			messenger.unregisterOutgoingPluginChannel(plugin, bukkitChannel);
			logger.debug("Đã hủy outgoing plugin channel: " + bukkitChannel);
		}
	}

	@Override
	public boolean send(Player target, PluginMessageChannel channel, byte[] payload) {
		String bukkitChannel = toBukkitChannel(channel);
		if (!outgoingChannels.contains(bukkitChannel)) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + bukkitChannel);
		}

		logger.debug("[TX] backend->proxy channel=" + toApiChannel(bukkitChannel)
			+ " target=" + target.getName()
			+ " bytes=" + payload.length);

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
		logger.debug("[RX] proxy->backend channel=" + apiChannel
			+ " source=" + player.getName()
			+ " bytes=" + message.length);

		PluginMessageHandler<Player> handler = incomingHandlers.get(channel);
		if (handler == null) {
			logger.debug("[RX] Không có handler cho channel=" + apiChannel);
			return;
		}

		handler.handle(new PluginMessageContext<>(PluginMessageChannel.of(apiChannel), player, message));
		logger.debug("[RX] Đã xử lý channel=" + apiChannel + " source=" + player.getName());
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
