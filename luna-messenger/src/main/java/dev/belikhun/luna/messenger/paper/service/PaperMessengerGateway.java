package dev.belikhun.luna.messenger.paper.service;

import com.loohp.interactivechat.api.InteractiveChatAPI;
import dev.belikhun.luna.core.api.config.ConfigValues;
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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class PaperMessengerGateway implements Listener {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final long CONTROL_COMMAND_DEDUP_WINDOW_MS = 250L;
	private static final long MENTION_REFRESH_DEBOUNCE_TICKS = 2L;

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PluginMessageBus<Player, Player> bus;
	private final ToastService toastService;
	private final BackendPlaceholderResolver placeholderResolver;
	private final Map<UUID, PendingRequest> pendingRequests;
	private final ConcurrentMap<UUID, String> networkPlayerNames;
	private final ConcurrentMap<UUID, Set<String>> mentionCompletionsByPlayer;
	private final ConcurrentMap<UUID, RecentControlCommand> recentControlCommands;
	private final AtomicBoolean mentionRefreshScheduled;
	private int timeoutTaskId;
	private final long requestTimeoutMillis;
	private final long timeoutCheckIntervalTicks;
	private final boolean timeoutEnabled;
	private final boolean interactiveChatDetected;

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
		this.recentControlCommands = new ConcurrentHashMap<>();
		this.mentionRefreshScheduled = new AtomicBoolean(false);
		this.timeoutTaskId = -1;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.timeoutCheckIntervalTicks = Math.max(1L, timeoutCheckIntervalTicks);
		this.timeoutEnabled = timeoutEnabled;
		this.interactiveChatDetected = plugin.getServer().getPluginManager().isPluginEnabled("InteractiveChat");
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		if (this.interactiveChatDetected) {
			this.logger.audit("Đã phát hiện InteractiveChat, sẽ dùng API để gửi chat component nếu khả dụng.");
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		networkPlayerNames.put(player.getUniqueId(), player.getName());
		scheduleMentionCompletionRefresh();
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		UUID playerId = player.getUniqueId();

		String removedName = networkPlayerNames.remove(playerId);
		Set<String> previous = mentionCompletionsByPlayer.remove(playerId);
		if (previous != null && !previous.isEmpty()) {
			player.removeCustomChatCompletions(previous);
		}

		pendingRequests.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
		recentControlCommands.remove(playerId);
		if (removedName != null || (previous != null && !previous.isEmpty())) {
			scheduleMentionCompletionRefresh();
		}
	}

	public java.util.Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String currentSender = senderName == null ? "" : senderName;

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
			String removed = networkPlayerNames.remove(presence.playerId());
			if (removed != null) {
				scheduleMentionCompletionRefresh();
			}
			return;
		}

		String playerName = presence.playerName();
		String previous = networkPlayerNames.put(presence.playerId(), playerName);
		if (!java.util.Objects.equals(previous, playerName)) {
			scheduleMentionCompletionRefresh();
		}
	}

	public void registerChannels() {
		bus.registerOutgoing(MessengerChannels.COMMAND);
		bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			Player player = plugin.getServer().getPlayer(result.receiverId());
			if (player != null) {
				if (result.resultType() == MessengerResultType.MENTION_ALERT) {
					sendComponentMessage(player, result.miniMessage(), result.metadata());
					playMentionSound(player, result.metadata().get("mention.sound"));
					boolean toastEnabled = ConfigValues.booleanValue(result.metadata().get("toast.enabled"), true);
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
					sendComponentMessage(player, result.miniMessage(), result.metadata());
					playResultSound(player, result.metadata().get("poke.sound"), result.metadata().get("poke.volume"), result.metadata().get("poke.pitch"), "poke");
				} else {
					sendComponentMessage(player, result.miniMessage(), result.metadata());
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

	private void sendComponentMessage(Player player, String miniMessage, Map<String, String> metadata) {
		String payload = miniMessage == null ? "" : miniMessage;
		Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
		Component component = MM.deserialize(payload);

		if (!interactiveChatDetected) {
			player.sendRichMessage(payload);
			return;
		}

		try {
			String senderId = safeMetadata.getOrDefault("sender_id", "");
			if (!senderId.isBlank()) {
				try {
					String markedMessage = InteractiveChatAPI.markSender(payload, UUID.fromString(senderId));
					component = MM.deserialize(markedMessage);
				} catch (Throwable ignored) {
					// Marking sender is optional; continue with standard IC send.
				}
			}

			String json = GsonComponentSerializer.gson().serialize(component);
			com.loohp.interactivechat.libs.net.kyori.adventure.text.Component interactiveChatComponent =
				com.loohp.interactivechat.utils.InteractiveChatComponentSerializer.gson().deserialize(json);
			InteractiveChatAPI.sendMessage(player, interactiveChatComponent);
		} catch (Throwable throwable) {
			logger.debug("InteractiveChat API unavailable hoặc lỗi invoke, fallback sendRichMessage: " + throwable.getMessage());
			player.sendRichMessage(payload);
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
		recentControlCommands.clear();
		mentionRefreshScheduled.set(false);
		if (timeoutTaskId != -1) {
			plugin.getServer().getScheduler().cancelTask(timeoutTaskId);
			timeoutTaskId = -1;
		}
	}

	private void scheduleMentionCompletionRefresh() {
		if (!mentionRefreshScheduled.compareAndSet(false, true)) {
			return;
		}

		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			try {
				refreshMentionCompletionsForAll();
			} finally {
				mentionRefreshScheduled.set(false);
			}
		}, MENTION_REFRESH_DEBOUNCE_TICKS);
	}

	private void refreshMentionCompletionsForAll() {
		Set<String> allMentionTargets = snapshotMentionTargets();
		for (Player online : plugin.getServer().getOnlinePlayers()) {
			refreshMentionCompletions(online, allMentionTargets);
		}
	}

	private void refreshMentionCompletions(Player player) {
		refreshMentionCompletions(player, snapshotMentionTargets());
	}

	private void refreshMentionCompletions(Player player, Set<String> allMentionTargets) {
		Set<String> completions = new LinkedHashSet<>(allMentionTargets);
		if (player != null && player.getName() != null && !player.getName().isBlank()) {
			completions.remove("@" + player.getName());
		}

		Set<String> applied = Set.copyOf(completions);
		Set<String> previous = mentionCompletionsByPlayer.get(player.getUniqueId());
		if (previous != null && previous.equals(applied)) {
			return;
		}

		mentionCompletionsByPlayer.put(player.getUniqueId(), applied);
		if (previous != null && !previous.isEmpty()) {
			player.removeCustomChatCompletions(previous);
		}
		if (!applied.isEmpty()) {
			player.addCustomChatCompletions(applied);
		}
	}

	private Set<String> snapshotMentionTargets() {
		Set<String> targets = new LinkedHashSet<>();
		for (String name : networkPlayerNames.values()) {
			if (name == null || name.isBlank()) {
				continue;
			}
			targets.add("@" + name);
		}
		return targets;
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
		if (isDuplicateControlCommand(player.getUniqueId(), commandType, argument, targetName)) {
			return;
		}

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

	private boolean isDuplicateControlCommand(UUID playerId, MessengerCommandType commandType, String argument, String targetName) {
		if (commandType != MessengerCommandType.SWITCH_NETWORK
			&& commandType != MessengerCommandType.SWITCH_SERVER
			&& commandType != MessengerCommandType.SWITCH_DIRECT) {
			return false;
		}

		String normalizedArgument = argument == null ? "" : argument;
		String normalizedTarget = targetName == null ? "" : targetName;
		String fingerprint = commandType.name() + "|" + normalizedArgument + "|" + normalizedTarget;
		long now = System.currentTimeMillis();

		RecentControlCommand previous = recentControlCommands.get(playerId);
		if (previous != null
			&& previous.fingerprint().equals(fingerprint)
			&& now - previous.atMillis() < CONTROL_COMMAND_DEDUP_WINDOW_MS) {
			return true;
		}

		recentControlCommands.put(playerId, new RecentControlCommand(fingerprint, now));
		return false;
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

	private record RecentControlCommand(String fingerprint, long atMillis) {
	}
}
