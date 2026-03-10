package dev.belikhun.luna.messenger.paper.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperMessengerGateway {
	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PluginMessageBus<Player, Player> bus;
	private final BackendPlaceholderResolver placeholderResolver;
	private final Map<UUID, PendingRequest> pendingRequests;
	private int timeoutTaskId;
	private final long requestTimeoutMillis;
	private final long timeoutCheckIntervalTicks;
	private final boolean timeoutEnabled;

	public PaperMessengerGateway(
		JavaPlugin plugin,
		LunaLogger logger,
		PluginMessageBus<Player, Player> bus,
		BackendPlaceholderResolver placeholderResolver,
		long requestTimeoutMillis,
		long timeoutCheckIntervalTicks,
		boolean timeoutEnabled
	) {
		this.plugin = plugin;
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.placeholderResolver = placeholderResolver;
		this.pendingRequests = new ConcurrentHashMap<>();
		this.timeoutTaskId = -1;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.timeoutCheckIntervalTicks = Math.max(1L, timeoutCheckIntervalTicks);
		this.timeoutEnabled = timeoutEnabled;
	}

	public void registerChannels() {
		bus.registerOutgoing(MessengerChannels.COMMAND);
		bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			Player player = plugin.getServer().getPlayer(result.receiverId());
			if (player != null) {
				if (result.resultType() == MessengerResultType.MENTION_ALERT) {
					player.sendRichMessage(result.miniMessage());
					boolean toastEnabled = parseBoolean(result.metadata().get("toast.enabled"), true);
					if (toastEnabled) {
						String titleText = result.metadata().getOrDefault("toast.title", "<yellow>Bạn được nhắc đến</yellow>");
						String subtitleText = result.metadata().getOrDefault("toast.subtitle", "<white>Kiểm tra khung chat</white>");
						long fadeIn = parseLong(result.metadata().get("toast.fade-in-ms"), 200L);
						long stay = parseLong(result.metadata().get("toast.stay-ms"), 2000L);
						long fadeOut = parseLong(result.metadata().get("toast.fade-out-ms"), 300L);
						player.showTitle(Title.title(
							net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(titleText),
							net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(subtitleText),
							Title.Times.times(Duration.ofMillis(Math.max(0L, fadeIn)), Duration.ofMillis(Math.max(0L, stay)), Duration.ofMillis(Math.max(0L, fadeOut)))
						));
					}
				} else {
					player.sendRichMessage(result.miniMessage());
				}
				if (result.correlationId() != null) {
					PendingRequest pending = pendingRequests.get(result.correlationId());
					if (pending != null && pending.playerId().equals(player.getUniqueId())) {
						pendingRequests.remove(result.correlationId());
					}
					logger.audit("Đã nhận phản hồi result=" + result.resultType().name() + " reqId=" + result.correlationId() + " cho " + player.getName());
				}
			}
			return PluginMessageDispatchResult.HANDLED;
		});

		if (timeoutEnabled) {
			timeoutTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::checkPendingTimeouts, timeoutCheckIntervalTicks, timeoutCheckIntervalTicks);
		}
	}

	public void switchNetwork(Player player) {
		send(player, MessengerCommandType.SWITCH_NETWORK, "");
	}

	public void switchServer(Player player) {
		send(player, MessengerCommandType.SWITCH_SERVER, "");
	}

	public void switchDirect(Player player, String targetName) {
		send(player, MessengerCommandType.SWITCH_DIRECT, targetName);
	}

	public void sendReply(Player player, String message) {
		send(player, MessengerCommandType.SEND_REPLY, message);
	}

	public void sendChat(Player player, String message) {
		send(player, MessengerCommandType.SEND_CHAT, message);
	}

	public void close() {
		bus.unregisterOutgoing(MessengerChannels.COMMAND);
		bus.unregisterIncoming(MessengerChannels.RESULT);
		pendingRequests.clear();
		if (timeoutTaskId != -1) {
			plugin.getServer().getScheduler().cancelTask(timeoutTaskId);
			timeoutTaskId = -1;
		}
	}

	private void send(Player player, MessengerCommandType commandType, String argument) {
		String server = plugin.getServer().getName();
		UUID requestId = UUID.randomUUID();
		Map<String, String> internalValues = new LinkedHashMap<>();
		internalValues.put("sender_name", player.getName());
		internalValues.put("player_name", player.getName());
		internalValues.put("server_name", server);
		internalValues.put("sender_server", server);
		if (commandType == MessengerCommandType.SWITCH_DIRECT) {
			internalValues.put("target_name", argument == null ? "" : argument);
		}

		var resolution = placeholderResolver.resolve(new PlaceholderResolutionRequest(
			player.getUniqueId(),
			player.getName(),
			server,
			argument,
			internalValues
		));
		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			player.getUniqueId(),
			player.getName(),
			server,
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);
		PluginMessageWriter writer = PluginMessageWriter.create();
		request.writeTo(writer);
		bus.send(player, MessengerChannels.COMMAND, writer.toByteArray());
		pendingRequests.put(requestId, new PendingRequest(player.getUniqueId(), commandType, System.currentTimeMillis()));
		logger.audit("Đã gửi command " + commandType.name() + " reqId=" + requestId + " cho " + player.getName());
	}

	private void checkPendingTimeouts() {
		if (!timeoutEnabled) {
			return;
		}

		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, PendingRequest> entry : pendingRequests.entrySet()) {
			PendingRequest pending = entry.getValue();
			if (now - pending.createdAtEpochMillis() < requestTimeoutMillis) {
				continue;
			}

			if (!pendingRequests.remove(entry.getKey(), pending)) {
				continue;
			}

			Player player = plugin.getServer().getPlayer(pending.playerId());
			if (player != null) {
				player.sendRichMessage("<red>❌ Hệ thống chat liên server đang chậm. Vui lòng thử lại.</red>");
			}
			logger.warn("Timeout command=" + pending.commandType().name() + " reqId=" + entry.getKey());
		}
	}

	private long parseLong(String text, long fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}

		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private boolean parseBoolean(String text, boolean fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}
		return Boolean.parseBoolean(text.trim());
	}

	private record PendingRequest(UUID playerId, MessengerCommandType commandType, long createdAtEpochMillis) {
	}
}
