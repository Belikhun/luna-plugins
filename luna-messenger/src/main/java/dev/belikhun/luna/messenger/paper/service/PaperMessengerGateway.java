package dev.belikhun.luna.messenger.paper.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.paper.toast.ToastService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class PaperMessengerGateway {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PluginMessageBus<Player, Player> bus;
	private final ToastService toastService;
	private final BackendPlaceholderResolver placeholderResolver;
	private final Map<UUID, PendingRequest> pendingRequests;
	private final ConcurrentMap<UUID, String> networkPlayerNames;
	private final ConcurrentMap<UUID, Set<String>> mentionCompletionsByPlayer;
	private int timeoutTaskId;
	private final long requestTimeoutMillis;
	private final long timeoutCheckIntervalTicks;
	private final boolean timeoutEnabled;

	public PaperMessengerGateway(
		JavaPlugin plugin,
		LunaLogger logger,
		PluginMessageBus<Player, Player> bus,
		ToastService toastService,
		BackendPlaceholderResolver placeholderResolver,
		long requestTimeoutMillis,
		long timeoutCheckIntervalTicks,
		boolean timeoutEnabled
	) {
		this.plugin = plugin;
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.toastService = toastService;
		this.placeholderResolver = placeholderResolver;
		this.pendingRequests = new ConcurrentHashMap<>();
		this.networkPlayerNames = new ConcurrentHashMap<>();
		this.mentionCompletionsByPlayer = new ConcurrentHashMap<>();
		this.timeoutTaskId = -1;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.timeoutCheckIntervalTicks = Math.max(1L, timeoutCheckIntervalTicks);
		this.timeoutEnabled = timeoutEnabled;
	}

	public java.util.Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String currentSender = senderName == null ? "" : senderName;

		for (Player online : plugin.getServer().getOnlinePlayers()) {
			networkPlayerNames.put(online.getUniqueId(), online.getName());
		}

		return networkPlayerNames.values().stream()
			.filter(name -> name != null && !name.isBlank())
			.filter(name -> !name.equalsIgnoreCase(currentSender))
			.filter(name -> token.isEmpty() || name.regionMatches(true, 0, token, 0, token.length()))
			.distinct()
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.limit(20)
			.collect(Collectors.toList());
	}

	private void handlePresence(MessengerPresenceMessage presence) {
		if (presence == null) {
			return;
		}

		MessengerPresenceType type = presence.presenceType();
		if (type == MessengerPresenceType.LEAVE) {
			networkPlayerNames.remove(presence.playerId());
			scheduleMentionCompletionRefresh();
			return;
		}

		networkPlayerNames.put(presence.playerId(), presence.playerName());
		scheduleMentionCompletionRefresh();
	}

	public void registerChannels() {
		bus.registerOutgoing(MessengerChannels.COMMAND);
		bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			Player player = plugin.getServer().getPlayer(result.receiverId());
			if (player != null) {
				if (result.resultType() == MessengerResultType.MENTION_ALERT) {
					player.sendRichMessage(result.miniMessage());
					playMentionSound(player, result.metadata().get("mention.sound"));
					boolean toastEnabled = parseBoolean(result.metadata().get("toast.enabled"), true);
					if (toastEnabled) {
						String titleText = result.metadata().getOrDefault("toast.title", "<yellow>Bạn được nhắc đến</yellow>");
						String subtitleText = result.metadata().getOrDefault("toast.subtitle", "<white>Kiểm tra khung chat</white>");
						Component toastTitle = MM.deserialize(titleText);
						Component toastSubtitle = MM.deserialize(subtitleText);
						ToastService.ToastResult toastResult = toastService.sendOneShot(
							player,
							"mention_toast",
							toastTitle,
							toastSubtitle
						);
						if (!toastResult.success()) {
							logger.audit("MENTION_TOAST_FAIL player=" + player.getName()
								+ " reason=" + toastResult.failureReason()
								+ " title=" + titleText
								+ " subtitle=" + subtitleText);
						}
					}
				} else if (result.resultType() == MessengerResultType.POKE_ALERT) {
					player.sendRichMessage(result.miniMessage());
					playResultSound(player, result.metadata().get("poke.sound"), result.metadata().get("poke.volume"), result.metadata().get("poke.pitch"), "poke");
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
		bus.registerIncoming(MessengerChannels.PRESENCE, context -> {
			MessengerPresenceMessage presence = MessengerPresenceMessage.readFrom(context.reader());
			handlePresence(presence);
			return PluginMessageDispatchResult.HANDLED;
		});

		for (Player online : plugin.getServer().getOnlinePlayers()) {
			networkPlayerNames.put(online.getUniqueId(), online.getName());
		}
		refreshMentionCompletionsForAll();

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

	public void sendDirect(Player player, String targetName, String message) {
		send(player, MessengerCommandType.SEND_DIRECT, message, targetName);
	}

	public void sendPoke(Player player, String targetName) {
		send(player, MessengerCommandType.SEND_POKE, "", targetName);
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
		bus.unregisterIncoming(MessengerChannels.PRESENCE);
		clearMentionCompletions();
		pendingRequests.clear();
		networkPlayerNames.clear();
		mentionCompletionsByPlayer.clear();
		if (timeoutTaskId != -1) {
			plugin.getServer().getScheduler().cancelTask(timeoutTaskId);
			timeoutTaskId = -1;
		}
	}

	private void scheduleMentionCompletionRefresh() {
		plugin.getServer().getScheduler().runTask(plugin, this::refreshMentionCompletionsForAll);
	}

	private void refreshMentionCompletionsForAll() {
		for (Player online : plugin.getServer().getOnlinePlayers()) {
			refreshMentionCompletions(online);
		}
	}

	private void refreshMentionCompletions(Player player) {
		Set<String> completions = new LinkedHashSet<>();
		for (String name : networkPlayerNames.values()) {
			if (name == null || name.isBlank() || name.equalsIgnoreCase(player.getName())) {
				continue;
			}
			completions.add("@" + name);
		}

		Set<String> applied = Set.copyOf(completions);
		Set<String> previous = mentionCompletionsByPlayer.put(player.getUniqueId(), applied);
		if (previous != null && !previous.isEmpty()) {
			player.removeCustomChatCompletions(previous);
		}
		if (!applied.isEmpty()) {
			player.addCustomChatCompletions(applied);
		}
	}

	private void clearMentionCompletions() {
		for (Player online : plugin.getServer().getOnlinePlayers()) {
			Set<String> previous = mentionCompletionsByPlayer.get(online.getUniqueId());
			if (previous == null || previous.isEmpty()) {
				continue;
			}
			online.removeCustomChatCompletions(previous);
		}
	}

	private void send(Player player, MessengerCommandType commandType, String argument) {
		send(player, commandType, argument, null);
	}

	private void send(Player player, MessengerCommandType commandType, String argument, String targetName) {
		String server = plugin.getServer().getName();
		UUID requestId = UUID.randomUUID();
		Map<String, String> internalValues = new LinkedHashMap<>();
		internalValues.put("sender_name", player.getName());
		internalValues.put("player_name", player.getName());
		internalValues.put("server_name", server);
		internalValues.put("sender_server", server);
		if (commandType == MessengerCommandType.SWITCH_DIRECT
			|| commandType == MessengerCommandType.SEND_DIRECT
			|| commandType == MessengerCommandType.SEND_POKE) {
			String directTarget = targetName != null ? targetName : argument;
			internalValues.put("target_name", directTarget == null ? "" : directTarget);
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

	private boolean parseBoolean(String text, boolean fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}
		return Boolean.parseBoolean(text.trim());
	}

	private void playMentionSound(Player player, String configuredSound) {
		playResultSound(player, configuredSound, "1.0", "1.0", "mention");
	}

	private void playResultSound(Player player, String configuredSound, String volumeText, String pitchText, String label) {
		if (configuredSound == null || configuredSound.isBlank() || configuredSound.equalsIgnoreCase("none")) {
			return;
		}

		String normalized = configuredSound.trim().toLowerCase();
		if (!normalized.contains(":")) {
			normalized = normalized.replace('_', '.');
			normalized = "minecraft:" + normalized;
		}

		float volume = parseFloat(volumeText, 1f);
		float pitch = parseFloat(pitchText, 1f);

		try {
			NamespacedKey key = NamespacedKey.fromString(normalized);
			if (key == null) {
				logger.debug("Sound " + label + " không hợp lệ cho " + player.getName() + ": " + configuredSound);
				return;
			}

			Sound sound = Registry.SOUNDS.get(key);
			if (sound == null) {
				logger.debug("Không tìm thấy sound " + label + " cho " + player.getName() + ": " + normalized);
				return;
			}

			player.playSound(player.getLocation(), sound, volume, pitch);
		} catch (Throwable throwable) {
			logger.debug("Không thể phát sound " + label + " cho " + player.getName() + ": " + configuredSound);
		}
	}

	private float parseFloat(String text, float fallback) {
		if (text == null || text.isBlank()) {
			return fallback;
		}

		try {
			return Float.parseFloat(text.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private record PendingRequest(UUID playerId, MessengerCommandType commandType, long createdAtEpochMillis) {
	}
}
