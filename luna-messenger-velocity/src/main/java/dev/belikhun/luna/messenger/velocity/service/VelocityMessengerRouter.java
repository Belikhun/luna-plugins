package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessagingContext;
import dev.belikhun.luna.core.api.messenger.MessagingContextType;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.ProxyMessageTemplateRenderer;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VelocityMessengerRouter {
	private static final int DISCORD_LOOP_WINDOW_MS = 15000;
	private static final int MAX_DISCORD_FINGERPRINTS = 4096;
	private static final int MAX_SEEN_PLAYERS = 100000;
	private static final long POKE_STREAK_WINDOW_MS = 15000L;
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
		.character('&')
		.hexColors()
		.useUnusualXRepeatedCharacterHexFormat()
		.build();
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
	private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("&?#([0-9a-fA-F]{6})");
	private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,16})");
	private static final Pattern MINI_TAG_PATTERN = Pattern.compile("<[^>]+>");
	private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("(?i)[&§][0-9A-FK-ORX]");

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final PluginMessageBus<Object, Object> bus;
	private volatile VelocityMessengerConfig config;
	private final ProxyMessageTemplateRenderer renderer;
	private final VelocityMiniPlaceholderResolver miniPlaceholderResolver;
	private final LuckPermsService luckPermsService;
	private volatile DiscordBridgeGateway discordBridge;
	private final Map<UUID, MessagingContext> contextByPlayer;
	private final Map<UUID, UUID> lastReplyByPlayer;
	private final Map<UUID, MuteRecord> mutedPlayers;
	private final Map<UUID, SpySubscription> spySubscriptions;
	private final Map<UUID, RateLimitState> rateLimitStates;
	private final Map<PokeStreakKey, PokeStreakState> pokeStreaks;
	private final Map<UUID, String> lastKnownServerByPlayer;
	private final Map<UUID, Long> playerSessionStartedAt;
	private final Map<UUID, String> pendingSelfPresenceByPlayer;
	private final Set<UUID> seenPlayers;
	private final ArrayDeque<UUID> seenPlayersOrder;
	private final Map<String, Long> recentDiscordOutboundFingerprints;

	public VelocityMessengerRouter(
		ProxyServer proxyServer,
		LunaLogger logger,
		PluginMessageBus<Object, Object> bus,
		VelocityMessengerConfig config,
		ProxyMessageTemplateRenderer renderer,
		LuckPermsService luckPermsService,
		DiscordBridgeGateway discordBridge
	) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Router");
		this.bus = bus;
		this.config = config;
		this.renderer = renderer;
		this.miniPlaceholderResolver = new VelocityMiniPlaceholderResolver();
		this.luckPermsService = luckPermsService;
		this.discordBridge = discordBridge;
		this.contextByPlayer = new ConcurrentHashMap<>();
		this.lastReplyByPlayer = new ConcurrentHashMap<>();
		this.mutedPlayers = new ConcurrentHashMap<>();
		this.spySubscriptions = new ConcurrentHashMap<>();
		this.rateLimitStates = new ConcurrentHashMap<>();
		this.pokeStreaks = new ConcurrentHashMap<>();
		this.lastKnownServerByPlayer = new ConcurrentHashMap<>();
		this.playerSessionStartedAt = new ConcurrentHashMap<>();
		this.pendingSelfPresenceByPlayer = new ConcurrentHashMap<>();
		this.seenPlayers = ConcurrentHashMap.newKeySet();
		this.seenPlayersOrder = new ArrayDeque<>();
		this.recentDiscordOutboundFingerprints = new ConcurrentHashMap<>();
	}

	public void registerChannels() {
		bus.registerIncoming(MessengerChannels.COMMAND, context -> {
			MessengerCommandRequest request = MessengerCommandRequest.readFrom(context.reader());
			handle(request);
			return PluginMessageDispatchResult.HANDLED;
		});
		bus.registerOutgoing(MessengerChannels.RESULT);
		bus.registerOutgoing(MessengerChannels.PRESENCE);
	}

	public void close() {
		bus.unregisterIncoming(MessengerChannels.COMMAND);
		bus.unregisterOutgoing(MessengerChannels.RESULT);
		bus.unregisterOutgoing(MessengerChannels.PRESENCE);
		contextByPlayer.clear();
		lastReplyByPlayer.clear();
		mutedPlayers.clear();
		spySubscriptions.clear();
		rateLimitStates.clear();
		pokeStreaks.clear();
		lastKnownServerByPlayer.clear();
		playerSessionStartedAt.clear();
		pendingSelfPresenceByPlayer.clear();
		seenPlayers.clear();
		seenPlayersOrder.clear();
		recentDiscordOutboundFingerprints.clear();
	}

	public void flushPendingSelfPresence(Player player) {
		if (player == null) {
			return;
		}

		String pending = pendingSelfPresenceByPlayer.remove(player.getUniqueId());
		if (pending == null || pending.isBlank()) {
			return;
		}

		sendResult(player, MessengerResultType.NETWORK_CHAT, pending, null);
	}

	public PersistentState snapshotPersistentState() {
		Map<UUID, PersistedContext> contexts = new HashMap<>();
		for (Map.Entry<UUID, MessagingContext> entry : contextByPlayer.entrySet()) {
			MessagingContext context = entry.getValue();
			if (context == null) {
				continue;
			}
			contexts.put(entry.getKey(), new PersistedContext(context.type().name(), context.directTargetId(), context.directTargetName()));
		}

		Map<UUID, UUID> replies = new HashMap<>(lastReplyByPlayer);
		Map<UUID, PersistedMute> mutes = new HashMap<>();
		for (Map.Entry<UUID, MuteRecord> entry : mutedPlayers.entrySet()) {
			MuteRecord mute = entry.getValue();
			mutes.put(entry.getKey(), new PersistedMute(mute.actor(), mute.reason(), mute.mutedAtEpochMs(), mute.expiresAtEpochMs()));
		}

		return new PersistentState(contexts, replies, mutes, Set.copyOf(seenPlayers));
	}

	public void restorePersistentState(PersistentState state) {
		if (state == null) {
			return;
		}

		contextByPlayer.clear();
		for (Map.Entry<UUID, PersistedContext> entry : state.contexts().entrySet()) {
			PersistedContext context = entry.getValue();
			if (context == null || context.type() == null || context.type().isBlank()) {
				continue;
			}

			try {
				MessagingContextType contextType = MessagingContextType.byName(context.type());
				MessagingContext restoredContext = switch (contextType) {
					case NETWORK -> MessagingContext.network();
					case SERVER -> MessagingContext.server();
					case DIRECT -> {
						if (context.directTargetId() == null) {
							yield null;
						}
						yield MessagingContext.direct(context.directTargetId(), context.directTargetName());
					}
				};

				if (restoredContext != null) {
					contextByPlayer.put(entry.getKey(), restoredContext);
				}
			} catch (Exception ignored) {
			}
		}

		lastReplyByPlayer.clear();
		lastReplyByPlayer.putAll(state.lastReplyByPlayer());

		mutedPlayers.clear();
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, PersistedMute> entry : state.mutedPlayers().entrySet()) {
			PersistedMute mute = entry.getValue();
			if (mute == null) {
				continue;
			}
			if (mute.expiresAtEpochMs() != null && mute.expiresAtEpochMs() <= now) {
				continue;
			}
			mutedPlayers.put(entry.getKey(), new MuteRecord(mute.actor(), mute.reason(), mute.mutedAtEpochMs(), mute.expiresAtEpochMs()));
		}

		seenPlayers.clear();
		seenPlayers.addAll(state.seenPlayers());
		seenPlayersOrder.clear();
		seenPlayersOrder.addAll(seenPlayers);
		trimSeenPlayers();
	}

	private boolean markSeenAndCheckFirstJoin(UUID playerId) {
		if (!seenPlayers.add(playerId)) {
			return false;
		}

		seenPlayersOrder.addLast(playerId);
		trimSeenPlayers();
		return true;
	}

	private void trimSeenPlayers() {
		while (seenPlayers.size() > MAX_SEEN_PLAYERS) {
			UUID oldest = seenPlayersOrder.pollFirst();
			if (oldest == null) {
				break;
			}

			seenPlayers.remove(oldest);
		}
	}

	public void reloadRuntime(VelocityMessengerConfig newConfig, DiscordBridgeGateway newDiscordBridge) {
		this.config = newConfig;
		this.discordBridge = newDiscordBridge;
		logger.audit("Đã cập nhật runtime config cho Messenger router.");
	}

	public void publishPresenceSnapshot() {
		for (Player player : proxyServer.getAllPlayers()) {
			String serverName = player.getCurrentServer()
				.map(connection -> connection.getServerInfo().getName())
				.orElse("");
			sendPresenceUpdate(new MessengerPresenceMessage(
				MessengerPresenceMessage.CURRENT_PROTOCOL,
				MessengerPresenceType.JOIN,
				player.getUniqueId(),
				player.getUsername(),
				"",
				serverName,
				false
			));
		}
	}

	private void handle(MessengerCommandRequest request) {
		Player sender = proxyServer.getPlayer(request.senderId()).orElse(null);
		if (sender == null) {
			return;
		}

		UUID correlationId = request.requestId();
		logger.audit("RX command=" + request.commandType().name() + " reqId=" + correlationId + " sender=" + sender.getUsername());

		switch (request.commandType()) {
			case SWITCH_NETWORK -> {
				contextByPlayer.put(sender.getUniqueId(), MessagingContext.network());
				sendInfo(sender, "<green>✔ Đã chuyển sang kênh <white>mạng</white>.</green>", correlationId);
			}
			case SWITCH_SERVER -> {
				contextByPlayer.put(sender.getUniqueId(), MessagingContext.server());
				sendInfo(sender, "<green>✔ Đã chuyển sang kênh <white>máy chủ</white>.</green>", correlationId);
			}
			case SWITCH_DIRECT -> handleSwitchDirect(sender, request.argument(), correlationId);
			case SEND_DIRECT -> handleSendDirect(sender, request.argument(), request.resolvedValues(), correlationId);
			case SEND_POKE -> handlePoke(sender, request.resolvedValues(), correlationId);
			case SEND_CHAT -> routeChat(sender, request.argument(), request.resolvedValues(), correlationId);
			case SEND_REPLY -> handleReply(sender, request.argument(), request.resolvedValues(), correlationId);
		}
	}

	private void handlePoke(Player sender, Map<String, String> resolvedValues, UUID correlationId) {
		if (checkRateLimit(sender, correlationId)) {
			return;
		}

		if (checkMuted(sender, correlationId)) {
			return;
		}

		String targetName = resolvedValues.getOrDefault("target_name", "").trim();
		if (targetName.isEmpty()) {
			sendError(sender, "<red>❌ Không tìm thấy người chơi để chọc.</red>", correlationId);
			return;
		}

		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			sendError(sender, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>", correlationId);
			return;
		}

		if (target.getUniqueId().equals(sender.getUniqueId())) {
			sendError(sender, "<yellow>ℹ Bạn không thể tự chọc chính mình.</yellow>", correlationId);
			return;
		}

		int streak = nextPokeStreak(sender.getUniqueId(), target.getUniqueId());
		sendInfo(sender, pokeSenderMessage(target.getUsername(), streak), correlationId);
		sendResult(target, MessengerResultType.POKE_ALERT, pokeTargetMessage(sender.getUsername(), streak), null, pokeReceiverMetadata(streak));
		logger.audit("POKE " + sender.getUsername() + " -> " + target.getUsername() + " streak=" + streak);
	}

	private int nextPokeStreak(UUID senderId, UUID targetId) {
		long now = System.currentTimeMillis();
		pokeStreaks.entrySet().removeIf(entry -> now - entry.getValue().lastPokeAtEpochMs() > (POKE_STREAK_WINDOW_MS * 2));

		PokeStreakKey key = new PokeStreakKey(senderId, targetId);
		PokeStreakState existing = pokeStreaks.get(key);
		if (existing == null || now - existing.lastPokeAtEpochMs() > POKE_STREAK_WINDOW_MS) {
			pokeStreaks.put(key, new PokeStreakState(1, now));
			return 1;
		}

		int next = existing.streak() + 1;
		pokeStreaks.put(key, new PokeStreakState(next, now));
		return next;
	}

	private Map<String, String> pokeReceiverMetadata(int streak) {
		String soundKey;
		float pitch;
		switch (streakTier(streak)) {
			case 1 -> {
				soundKey = "minecraft:entity.experience_orb.pickup";
				pitch = 1.2f;
			}
			case 2 -> {
				soundKey = "minecraft:block.note_block.pling";
				pitch = 1.25f;
			}
			case 3 -> {
				soundKey = "minecraft:entity.player.levelup";
				pitch = 1.0f;
			}
			default -> {
				soundKey = "minecraft:entity.firework_rocket.twinkle";
				pitch = 0.95f;
			}
		}

		return Map.of(
			"poke.sound", soundKey,
			"poke.pitch", Float.toString(pitch),
			"poke.volume", "1.0"
		);
	}

	private int streakTier(int streak) {
		if (streak <= 1) {
			return 1;
		}
		if (streak <= 4) {
			return 2;
		}
		if (streak <= 8) {
			return 3;
		}
		return 4;
	}

	private String pokeSenderMessage(String targetName, int streak) {
		String escaped = escape(targetName);
		int tier = streakTier(streak);
		if (tier == 1) {
			return "<green>✔ Bạn đã chọc <white>" + escaped + "</white> <gray>( •̀ᴗ•́ )و</gray></green>";
		}
		if (tier == 2) {
			return "<aqua>⚡ Combo chọc x" + streak + " vào <white>" + escaped + "</white>!</aqua>";
		}
		if (tier == 3) {
			return "<gold>🔥 Chuỗi chọc x" + streak + "! <white>" + escaped + "</white> chắc chắn đã để ý bạn.</gold>";
		}

		return "<red>💥 MEGA CHỌC x" + streak + " vào <white>" + escaped + "</white>! <yellow>Bạn đang không thể bị cản lại!</yellow></red>";
	}

	private String pokeTargetMessage(String senderName, int streak) {
		String escaped = escape(senderName);
		int tier = streakTier(streak);
		if (tier == 1) {
			return "<gold>👉 <white>" + escaped + "</white> vừa chọc bạn!</gold>";
		}
		if (tier == 2) {
			return "<yellow>👉 <white>" + escaped + "</white> spam chọc x" + streak + "! Não bạn rung nhẹ rồi.</yellow>";
		}
		if (tier == 3) {
			return "<gold>⚠ <white>" + escaped + "</white> combo x" + streak + "! Bạn mở khóa danh hiệu bao cát dễ thương.</gold>";
		}

		return "<red>🚨 <white>" + escaped + "</white> MEGA CHỌC x" + streak + "! Về kể lại không ai tin luôn á.</red>";
	}

	private void handleSendDirect(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		if (checkRateLimit(sender, correlationId)) {
			return;
		}

		if (checkMuted(sender, correlationId)) {
			return;
		}

		String targetName = resolvedValues.getOrDefault("target_name", "").trim();
		if (targetName.isEmpty()) {
			sendError(sender, "<red>❌ Không tìm thấy người chơi để nhắn trực tiếp.</red>", correlationId);
			return;
		}

		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			sendError(sender, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>", correlationId);
			return;
		}

		sendDirect(sender, target, message, resolvedValues, correlationId, false);
	}

	private void handleSwitchDirect(Player sender, String targetName, UUID correlationId) {
		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			sendError(sender, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>", correlationId);
			return;
		}

		contextByPlayer.put(sender.getUniqueId(), MessagingContext.direct(target.getUniqueId(), target.getUsername()));
		sendInfo(sender, "<green>✔ Đã chuyển sang nhắn tin trực tiếp với <white>" + escape(target.getUsername()) + "</white>.</green>", correlationId);
	}

	private void handleReply(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		if (checkRateLimit(sender, correlationId)) {
			return;
		}

		if (checkMuted(sender, correlationId)) {
			return;
		}

		UUID targetId = lastReplyByPlayer.get(sender.getUniqueId());
		if (targetId == null) {
			sendError(sender, "<red>❌ Bạn chưa có người để trả lời gần nhất.</red>", correlationId);
			return;
		}

		Player target = proxyServer.getPlayer(targetId).orElse(null);
		if (target == null) {
			sendError(sender, "<red>❌ Người chơi đã thoát khỏi mạng.</red>", correlationId);
			return;
		}

		sendDirect(sender, target, message, resolvedValues, correlationId, true);
	}

	private void routeChat(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		if (checkRateLimit(sender, correlationId)) {
			return;
		}

		if (checkMuted(sender, correlationId)) {
			return;
		}

		MessagingContext context = contextByPlayer.computeIfAbsent(sender.getUniqueId(), ignored -> MessagingContext.network());
		if (context.type() == MessagingContextType.SERVER) {
			routeServer(sender, message, resolvedValues, correlationId);
			return;
		}
		if (context.type() == MessagingContextType.DIRECT) {
			Player target = context.directTargetId() == null ? null : proxyServer.getPlayer(context.directTargetId()).orElse(null);
			if (target == null) {
				contextByPlayer.put(sender.getUniqueId(), MessagingContext.network());
				sendError(sender, "<red>❌ Người chơi trong kênh trực tiếp không còn online.</red>", correlationId);
				sendInfo(sender, "<yellow>ℹ Đã tự động chuyển bạn về kênh <white>mạng</white>.</yellow>", correlationId);
				return;
			}
			sendDirect(sender, target, message, resolvedValues, correlationId, false);
			return;
		}
		routeNetwork(sender, message, resolvedValues, correlationId);
	}

	private void routeNetwork(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		String serverName = sender.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		String serverDisplay = config.serverDisplay(serverName);
		String serverColor = config.serverColor(serverName);
		String senderPrefix = resolvePresencePlayerPrefix(sender);
		String senderDisplay = resolvePlayerDisplay(sender, senderPrefix, sender.getUsername(), resolvedValues);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String channelName = config.discord().networkChannelName();
		String avatarUrl = resolveAvatarUrl(sender, resolvedValues);
		List<Player> recipients = List.copyOf(proxyServer.getAllPlayers());
		MentionProcessing mention = processMentions(sender, message, recipients);
		Map<String, String> internalValues = Map.of(
			"sender_name", sender.getUsername(),
			"server_name", serverName,
			"server_display", serverDisplay,
			"channel_name", channelName,
			"player_avatar_url", avatarUrl,
			"server_color", serverColor
		);
		String rendered = renderPlayerMessage(profile.networkFormat(), mention.renderedMessage(), internalValues, resolvedValues, sender, Map.of(
			"server_display", serverDisplay,
			"player_prefix", senderPrefix,
			"player_display", senderDisplay,
			"sender_display", senderDisplay,
			"receiver_display", ""
		));
		for (Player player : recipients) {
			sendResult(player, MessengerResultType.NETWORK_CHAT, rendered, correlationId);
			notifyMentionTarget(sender, player, mention.mentionedById());
		}
		logger.audit("CHAT[NETWORK] " + toConsoleText(rendered));

		// Chỉ tin nhắn network mới được bridge ra Discord.
		Map<String, String> outboundInternalValues = Map.of(
			"sender_name", sender.getUsername(),
			"server_name", serverName,
			"server_display", normalizeDiscordDisplayToken(serverDisplay),
			"channel_name", channelName,
			"player_avatar_url", avatarUrl,
			"message", stripLegacy(message),
			"server_color", serverColor
		);
		String outbound = renderWithStack(profile.discordOutboundNetworkFormat(), outboundInternalValues, resolvedValues, sender);
		Map<String, String> discordInternalValues = new HashMap<>(outboundInternalValues);
		discordInternalValues.put("discord_content", outbound);
		publishDiscord(config.discord().networkMessage(), discordInternalValues, resolvedValues, sender,
			DiscordOutboundMessage.DispatchType.PLAYER_CHAT);
	}

	private void routeServer(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		ServerConnection senderServer = sender.getCurrentServer().orElse(null);
		if (senderServer == null) {
			sendError(sender, "<red>❌ Không thể xác định máy chủ hiện tại.</red>", correlationId);
			return;
		}

		String serverName = senderServer.getServerInfo().getName();
		String serverDisplay = config.serverDisplay(serverName);
		String serverColor = config.serverColor(serverName);
		String senderPrefix = resolvePresencePlayerPrefix(sender);
		String senderDisplay = resolvePlayerDisplay(sender, senderPrefix, sender.getUsername(), resolvedValues);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		List<Player> recipients = new java.util.ArrayList<>();
		for (Player player : proxyServer.getAllPlayers()) {
			ServerConnection viewerServer = player.getCurrentServer().orElse(null);
			if (viewerServer == null) {
				continue;
			}
			if (viewerServer.getServerInfo().getName().equals(serverName)) {
				recipients.add(player);
			}
		}

		MentionProcessing mention = processMentions(sender, message, recipients);
		String rendered = renderPlayerMessage(profile.serverFormat(), mention.renderedMessage(), Map.of(
			"sender_name", sender.getUsername(),
			"server_name", serverName,
			"server_display", serverDisplay,
			"channel_name", "server",
			"player_avatar_url", resolveAvatarUrl(sender, resolvedValues),
			"server_color", serverColor
		), resolvedValues, sender, Map.of(
			"server_display", serverDisplay,
			"player_prefix", senderPrefix,
			"player_display", senderDisplay,
			"sender_display", senderDisplay,
			"receiver_display", ""
		));
		for (Player player : recipients) {
			sendResult(player, MessengerResultType.SERVER_CHAT, rendered, correlationId);
			notifyMentionTarget(sender, player, mention.mentionedById());
		}
		logger.audit("CHAT[SERVER:" + serverName + "] " + toConsoleText(rendered));
	}

	private void sendDirect(Player sender, Player target, String message, Map<String, String> resolvedValues, UUID correlationId, boolean replyMode) {
		String senderServer = sender.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		String senderServerDisplay = config.serverDisplay(senderServer);
		String senderServerColor = config.serverColor(senderServer);
		String senderPrefix = resolvePresencePlayerPrefix(sender);
		String targetPrefix = resolvePresencePlayerPrefix(target);
		String senderDisplay = resolvePlayerDisplay(sender, senderPrefix, sender.getUsername(), resolvedValues);
		String targetDisplay = resolvePlayerDisplay(target, targetPrefix, target.getUsername(), resolvedValues);
		String senderDisplayName = sender.getUsername();
		String targetDisplayName = target.getUsername();
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(senderServer);
		String toSenderTemplate = replyMode ? profile.replyToSenderFormat() : profile.directToSenderFormat();
		String toReceiverTemplate = replyMode ? profile.replyToReceiverFormat() : profile.directToReceiverFormat();
		String toSender = renderPlayerMessage(toSenderTemplate, message, Map.ofEntries(
			Map.entry("sender_name", sender.getUsername()),
			Map.entry("target_name", target.getUsername()),
			Map.entry("sender_player_name", sender.getUsername()),
			Map.entry("receiver_player_name", target.getUsername()),
			Map.entry("sender_player_displayname", senderDisplayName),
			Map.entry("receiver_player_displayname", targetDisplayName),
			Map.entry("server_name", senderServer),
			Map.entry("server_display", senderServerDisplay),
			Map.entry("channel_name", "direct"),
			Map.entry("player_avatar_url", resolveAvatarUrl(sender, resolvedValues)),
			Map.entry("server_color", senderServerColor)
		), resolvedValues, sender, Map.of(
			"server_display", senderServerDisplay,
			"player_prefix", senderPrefix,
			"sender_prefix", senderPrefix,
			"target_prefix", targetPrefix,
			"receiver_prefix", targetPrefix,
			"player_display", senderDisplay,
			"sender_display", senderDisplay,
			"receiver_display", targetDisplay
		));
		String toTarget = renderPlayerMessage(toReceiverTemplate, message, Map.ofEntries(
			Map.entry("sender_name", sender.getUsername()),
			Map.entry("target_name", target.getUsername()),
			Map.entry("sender_player_name", sender.getUsername()),
			Map.entry("receiver_player_name", target.getUsername()),
			Map.entry("sender_player_displayname", senderDisplayName),
			Map.entry("receiver_player_displayname", targetDisplayName),
			Map.entry("server_name", senderServer),
			Map.entry("server_display", senderServerDisplay),
			Map.entry("channel_name", "direct"),
			Map.entry("player_avatar_url", resolveAvatarUrl(target, resolvedValues)),
			Map.entry("server_color", senderServerColor)
		), resolvedValues, sender, Map.of(
			"server_display", senderServerDisplay,
			"player_prefix", senderPrefix,
			"sender_prefix", senderPrefix,
			"target_prefix", targetPrefix,
			"receiver_prefix", targetPrefix,
			"player_display", targetDisplay,
			"sender_display", senderDisplay,
			"receiver_display", targetDisplay
		));
		sendResult(sender, MessengerResultType.DIRECT_ECHO, toSender, correlationId);
		sendResult(target, MessengerResultType.DIRECT_CHAT, toTarget, correlationId);
		notifySpy(sender, target, message, senderDisplay, targetDisplay, replyMode);
		logger.audit("CHAT[DIRECT] " + sender.getUsername() + " -> " + target.getUsername() + ": " + toConsoleText(toSender));
		lastReplyByPlayer.put(sender.getUniqueId(), target.getUniqueId());
		lastReplyByPlayer.put(target.getUniqueId(), sender.getUniqueId());
	}

	private void notifySpy(Player sender, Player receiver, String rawMessage, String senderDisplay, String receiverDisplay, boolean replyMode) {
		if (spySubscriptions.isEmpty()) {
			return;
		}

		String messageMini = legacyToMini(rawMessage);
		for (Map.Entry<UUID, SpySubscription> entry : spySubscriptions.entrySet()) {
			UUID watcherId = entry.getKey();
			SpySubscription subscription = entry.getValue();
			if (subscription == null) {
				continue;
			}

			if (!subscription.matches(sender.getUniqueId(), receiver.getUniqueId())) {
				continue;
			}

			Player watcher = proxyServer.getPlayer(watcherId).orElse(null);
			if (watcher == null) {
				spySubscriptions.remove(watcherId, subscription);
				continue;
			}

			String modeText = replyMode ? "REPLY" : "DM";
			watcher.sendRichMessage(
				"<gray>[<yellow>SPY</yellow>]</gray> <gray>[" + modeText + "]</gray> "
					+ senderDisplay + " <gray>-></gray> " + receiverDisplay + "<gray>:</gray> <white>" + messageMini + "</white>"
			);
		}
	}

	public void routeInboundDiscordMessage(DiscordInboundMessage inboundMessage) {
		if (inboundMessage == null) {
			return;
		}

		String source = inboundMessage.source() == null ? "discord" : inboundMessage.source();
		String authorName = inboundMessage.authorName() == null ? "Discord" : inboundMessage.authorName();
		String authorUsername = inboundMessage.authorUsername() == null || inboundMessage.authorUsername().isBlank()
			? authorName
			: inboundMessage.authorUsername();
		String authorNickname = inboundMessage.authorNickname() == null || inboundMessage.authorNickname().isBlank()
			? authorName
			: inboundMessage.authorNickname();
		String message = inboundMessage.message() == null ? "" : inboundMessage.message();
		if (shouldDropInboundDiscordLoop(authorName, message, source)) {
			logger.audit("Bỏ qua Discord inbound do anti-loop: source=" + source + ", author=" + authorName);
			return;
		}

		// Discord -> network mặc định theo yêu cầu.
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer("");
		String channelName = config.discord().networkChannelName();
		String serverColor = config.serverColor("");
		String serverDisplay = config.serverDisplay("");
		String rendered = renderWithStack(profile.discordInboundNetworkFormat(), Map.ofEntries(
			Map.entry("discord_author", authorName),
			Map.entry("user_name", authorName),
			Map.entry("user_username", authorUsername),
			Map.entry("user_nickname", authorNickname),
			Map.entry("server_name", "proxy"),
			Map.entry("server_display", stripLegacy(serverDisplay)),
			Map.entry("channel_name", channelName),
			Map.entry("message", message),
			Map.entry("server_color", serverColor),
			Map.entry("discord_source", source),
			Map.entry("discord_message_id", inboundMessage.messageId() == null ? "" : inboundMessage.messageId()),
			Map.entry("discord_author_id", inboundMessage.authorId() == null ? "" : inboundMessage.authorId())
		), Map.of(), null);
		for (Player player : proxyServer.getAllPlayers()) {
			sendResult(player, MessengerResultType.NETWORK_CHAT, rendered, null);
		}
	}

	public void handlePlayerJoin(Player player, String connectedServerName) {
		boolean firstJoin = markSeenAndCheckFirstJoin(player.getUniqueId());
		String serverName = (connectedServerName == null || connectedServerName.isBlank())
			? player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("proxy")
			: connectedServerName;
		lastKnownServerByPlayer.put(player.getUniqueId(), serverName);
		playerSessionStartedAt.put(player.getUniqueId(), System.currentTimeMillis());
		sendPresenceUpdate(new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			firstJoin ? MessengerPresenceType.FIRST_JOIN : MessengerPresenceType.JOIN,
			player.getUniqueId(),
			player.getUsername(),
			"",
			serverName,
			firstJoin
		));

		String serverColor = config.serverColor(serverName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String playerPrefix = resolvePresencePlayerPrefix(player);
		String playerDisplay = resolvePlayerDisplay(player, playerPrefix, player.getUsername(), Map.of());
		String serverDisplay = config.serverDisplay(serverName);
		String presenceMessage = firstJoin
			? player.getUsername() + " đã vào mạng lần đầu"
			: player.getUsername() + " đã vào mạng";
		String joinTemplate = firstJoin ? profile.firstJoinNetworkFormat() : profile.joinNetworkFormat();
		String joinRendered = renderWithStack(
			joinTemplate,
			Map.ofEntries(
				Map.entry("sender_name", player.getUsername()),
				Map.entry("player_name", player.getUsername()),
				Map.entry("displayname", player.getUsername()),
				Map.entry("server_name", serverName),
				Map.entry("server_display", stripLegacy(serverDisplay)),
				Map.entry("channel_name", config.discord().networkChannelName()),
				Map.entry("server_color", serverColor),
				Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
				Map.entry("presence_type", firstJoin ? "FIRST_JOIN" : "JOIN")
			),
			Map.of(),
			player
		);
		joinRendered = injectUnescapedPlaceholders(joinRendered, Map.of(
			"player_prefix", playerPrefix,
			"server_display", serverDisplay,
			"message", presenceMessage,
			"player_display", playerDisplay,
			"sender_display", playerDisplay,
			"receiver_display", ""
		));
		boolean senderHandled = false;
		for (Player online : proxyServer.getAllPlayers()) {
			if (!canReceiveBroadcastPresence(player, online)) {
				continue;
			}

			if (online.getUniqueId().equals(player.getUniqueId())) {
				senderHandled = true;
			}

			String viewerRendered = formatPresenceForViewer(player, joinRendered);
			boolean delivered = sendResultIfConnected(online, MessengerResultType.NETWORK_CHAT, viewerRendered, null, Map.of());
			if (!delivered && online.getUniqueId().equals(player.getUniqueId())) {
				pendingSelfPresenceByPlayer.put(player.getUniqueId(), viewerRendered);
			}
		}

		if (!senderHandled) {
			String selfRendered = formatPresenceForViewer(player, joinRendered);
			boolean delivered = sendResultIfConnected(player, MessengerResultType.NETWORK_CHAT, selfRendered, null, Map.of());
			if (!delivered) {
				pendingSelfPresenceByPlayer.put(player.getUniqueId(), selfRendered);
			}
		}

		if (isSilentBroadcastSender(player)) {
			return;
		}

		publishDiscord(config.discord().joinMessage(), Map.of(
			"sender_name", player.getUsername(),
			"server_name", serverName,
			"server_display", normalizeDiscordDisplayToken(serverDisplay),
			"channel_name", config.discord().networkChannelName(),
			"server_color", serverColor,
			"player_avatar_url", resolveAvatarUrl(player, Map.of()),
			"presence_type", firstJoin ? "FIRST_JOIN" : "JOIN",
			"message", firstJoin
				? player.getUsername() + " đã vào mạng lần đầu"
				: player.getUsername() + " đã vào mạng"
		), Map.of(), player, DiscordOutboundMessage.DispatchType.BROADCAST);
	}

	public void handlePlayerLeave(Player player, String previousServerName) {
		UUID leavingId = player.getUniqueId();
		lastReplyByPlayer.remove(leavingId);
		rateLimitStates.remove(leavingId);
		pendingSelfPresenceByPlayer.remove(leavingId);
		long now = System.currentTimeMillis();
		Long sessionStartedAt = playerSessionStartedAt.remove(leavingId);
		String playtime = formatDuration(sessionStartedAt == null ? 0L : Math.max(0L, now - sessionStartedAt));
		lastReplyByPlayer.entrySet().removeIf(entry -> entry.getValue().equals(leavingId));
		contextByPlayer.entrySet().removeIf(entry -> {
			MessagingContext context = entry.getValue();
			return context != null && context.type() == MessagingContextType.DIRECT && leavingId.equals(context.directTargetId());
		});

		String serverName = resolveLeaveServerName(player, previousServerName);
		sendPresenceUpdate(new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.LEAVE,
			player.getUniqueId(),
			player.getUsername(),
			serverName,
			"",
			false
		));

		String serverColor = config.serverColor(serverName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String playerPrefix = resolvePresencePlayerPrefix(player);
		String playerDisplay = resolvePlayerDisplay(player, playerPrefix, player.getUsername(), Map.of());
		String serverDisplay = config.serverDisplay(serverName);
		String presenceMessage = player.getUsername() + " đã rời mạng";
		String leaveRendered = renderWithStack(
			profile.leaveNetworkFormat(),
			Map.ofEntries(
				Map.entry("sender_name", player.getUsername()),
				Map.entry("player_name", player.getUsername()),
				Map.entry("displayname", player.getUsername()),
				Map.entry("server_name", serverName),
				Map.entry("server_display", stripLegacy(serverDisplay)),
				Map.entry("channel_name", config.discord().networkChannelName()),
				Map.entry("server_color", serverColor),
				Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
				Map.entry("player_playtime", playtime),
				Map.entry("presence_type", "LEAVE")
			),
			Map.of(),
			player
		);
		leaveRendered = injectUnescapedPlaceholders(leaveRendered, Map.of(
			"player_prefix", playerPrefix,
			"server_display", serverDisplay,
			"message", presenceMessage,
			"player_playtime", playtime,
			"player_display", playerDisplay,
			"sender_display", playerDisplay,
			"receiver_display", ""
		));
		for (Player online : proxyServer.getAllPlayers()) {
			if (!canReceiveBroadcastPresence(player, online)) {
				continue;
			}

			String viewerRendered = formatPresenceForViewer(player, leaveRendered);
			sendResultIfConnected(online, MessengerResultType.NETWORK_CHAT, viewerRendered, null, Map.of());
		}

		if (isSilentBroadcastSender(player)) {
			lastKnownServerByPlayer.remove(leavingId);
			return;
		}

		publishDiscord(config.discord().leaveMessage(), Map.of(
			"sender_name", player.getUsername(),
			"server_name", serverName,
			"server_display", normalizeDiscordDisplayToken(serverDisplay),
			"channel_name", config.discord().networkChannelName(),
			"server_color", serverColor,
			"player_avatar_url", resolveAvatarUrl(player, Map.of()),
			"player_playtime", playtime,
			"message", player.getUsername() + " đã rời mạng"
		), Map.of(), player, DiscordOutboundMessage.DispatchType.BROADCAST);

		lastKnownServerByPlayer.remove(leavingId);
	}

	public void handleServerSwitch(Player player, String previousServerName, String currentServerName) {
		String toServerName = currentServerName == null ? "" : currentServerName.trim();
		if (toServerName.isBlank()) {
			toServerName = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		}
		if (toServerName.isBlank()) {
			toServerName = lastKnownServerByPlayer.getOrDefault(player.getUniqueId(), "");
		}
		if (toServerName.isBlank()) {
			toServerName = "proxy";
		}
		lastKnownServerByPlayer.put(player.getUniqueId(), toServerName);
		sendPresenceUpdate(new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.SWAP,
			player.getUniqueId(),
			player.getUsername(),
			previousServerName,
			toServerName,
			false
		));

		String toDisplay = config.serverDisplay(toServerName);
		String fromDisplay = config.serverDisplay(previousServerName);
		String toServerColor = config.serverColor(toServerName);
		String playerPrefix = resolvePresencePlayerPrefix(player);
		String playerDisplay = resolvePlayerDisplay(player, playerPrefix, player.getUsername(), Map.of());
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(toServerName);
		if (!profile.serverSwitchEnabled()) {
			return;
		}

		String rendered = renderWithStack(profile.serverSwitchFormat(), Map.ofEntries(
			Map.entry("sender_name", player.getUsername()),
			Map.entry("displayname", player.getUsername()),
			Map.entry("from_server", previousServerName),
			Map.entry("to_server", toServerName),
			Map.entry("server_display", stripLegacy(toDisplay)),
			Map.entry("from", stripLegacy(fromDisplay)),
			Map.entry("to", stripLegacy(toDisplay)),
			Map.entry("from_clean", stripLegacy(fromDisplay)),
			Map.entry("to_clean", stripLegacy(toDisplay)),
			Map.entry("presence_type", "SWAP"),
			Map.entry("server_name", toServerName),
			Map.entry("server_color", toServerColor)
		), Map.of(), player);
		rendered = injectUnescapedPlaceholders(rendered, Map.of(
			"player_prefix", playerPrefix,
			"from_display", fromDisplay,
			"to_display", toDisplay,
			"server_display", toDisplay,
			"player_display", playerDisplay,
			"sender_display", playerDisplay,
			"receiver_display", ""
		));
		boolean senderHandled = false;

		for (Player online : proxyServer.getAllPlayers()) {
			if (!canReceiveBroadcastPresence(player, online)) {
				continue;
			}

			if (online.getUniqueId().equals(player.getUniqueId())) {
				senderHandled = true;
			}

			String viewerRendered = formatPresenceForViewer(player, rendered);
			boolean delivered = sendResultIfConnected(online, MessengerResultType.NETWORK_CHAT, viewerRendered, null, Map.of());
			if (!delivered && online.getUniqueId().equals(player.getUniqueId())) {
				pendingSelfPresenceByPlayer.put(player.getUniqueId(), viewerRendered);
			}
		}

		if (!senderHandled) {
			String selfRendered = formatPresenceForViewer(player, rendered);
			boolean delivered = sendResultIfConnected(player, MessengerResultType.NETWORK_CHAT, selfRendered, null, Map.of());
			if (!delivered) {
				pendingSelfPresenceByPlayer.put(player.getUniqueId(), selfRendered);
			}
		}

		if (isSilentBroadcastSender(player)) {
			logger.debug("Switch server (silenced): " + player.getUsername() + " " + previousServerName + " -> " + toServerName);
			return;
		}

		publishDiscord(config.discord().switchMessage(), Map.ofEntries(
			Map.entry("sender_name", player.getUsername()),
			Map.entry("from_server", previousServerName),
			Map.entry("to_server", toServerName),
			Map.entry("from_display", normalizeDiscordDisplayToken(fromDisplay)),
			Map.entry("to_display", normalizeDiscordDisplayToken(toDisplay)),
			Map.entry("server_name", toServerName),
			Map.entry("server_display", normalizeDiscordDisplayToken(toDisplay)),
			Map.entry("channel_name", config.discord().networkChannelName()),
			Map.entry("server_color", toServerColor),
			Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
			Map.entry("presence_type", "SWAP"),
			Map.entry("message", player.getUsername() + " chuyển " + previousServerName + " -> " + toServerName)
		), Map.of(), player, DiscordOutboundMessage.DispatchType.BROADCAST);

		logger.debug("Switch server: " + player.getUsername() + " " + previousServerName + " -> " + toServerName);
	}

	private String resolveLeaveServerName(Player player, String previousServerName) {
		if (previousServerName != null && !previousServerName.isBlank()) {
			return previousServerName;
		}

		if (player != null) {
			String current = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
			if (!current.isBlank()) {
				return current;
			}

			String remembered = lastKnownServerByPlayer.get(player.getUniqueId());
			if (remembered != null && !remembered.isBlank()) {
				return remembered;
			}
		}

		return "proxy";
	}

	private boolean canReceiveBroadcastPresence(Player source, Player viewer) {
		if (source != null && viewer != null && source.getUniqueId().equals(viewer.getUniqueId())) {
			return true;
		}

		if (!isSilentBroadcastSender(source)) {
			return true;
		}

		return hasSilentBroadcastPermission(viewer);
	}

	private String formatPresenceForViewer(Player source, String rendered) {
		if (!isSilentBroadcastSender(source)) {
			return rendered;
		}

		String prefix = config.silentBroadcast().prefix();
		if (prefix == null || prefix.isBlank()) {
			return rendered;
		}

		return prefix + rendered;
	}

	private boolean isSilentBroadcastSender(Player player) {
		if (player == null) {
			return false;
		}

		return hasSilentBroadcastPermission(player);
	}

	private boolean hasSilentBroadcastPermission(Player player) {
		if (player == null) {
			return false;
		}

		VelocityMessengerConfig.SilentBroadcastConfig silent = config.silentBroadcast();
		if (silent == null || !silent.enabled()) {
			return false;
		}

		String permission = silent.permission();
		if (permission == null || permission.isBlank()) {
			return false;
		}

		return player.hasPermission(permission);
	}

	public ModerationResult muteByName(String actor, String targetName, String reason, Long durationMillis) {
		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			return new ModerationResult(false, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>");
		}

		MuteRecord existing = mutedPlayers.get(target.getUniqueId());
		if (existing != null) {
			return new ModerationResult(false, "<yellow>ℹ Người chơi <white>" + escape(target.getUsername()) + "</white> đã bị mute trước đó.</yellow>");
		}

		long now = System.currentTimeMillis();
		Long expiresAt = durationMillis == null ? null : now + durationMillis;
		mutedPlayers.put(target.getUniqueId(), new MuteRecord(actor, reason, now, expiresAt));

		if (expiresAt != null) {
			long effectiveDurationMillis = durationMillis == null ? 0L : durationMillis;
			String durationText = formatDuration(effectiveDurationMillis);
			sendError(target, "<red>❌ Bạn đã bị tắt chat toàn mạng trong <white>" + durationText + "</white>. Lý do: <white>" + escape(reason) + "</white></red>");
			return new ModerationResult(true, "<green>✔ Đã mute <white>" + escape(target.getUsername()) + "</white> trong <white>" + durationText + "</white>.</green>");
		}

		sendError(target, "<red>❌ Bạn đã bị tắt chat toàn mạng. Lý do: <white>" + escape(reason) + "</white></red>");
		return new ModerationResult(true, "<green>✔ Đã mute <white>" + escape(target.getUsername()) + "</white>.</green>");
	}

	public ModerationResult unmuteByName(String actor, String targetName) {
		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			return new ModerationResult(false, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>");
		}

		MuteRecord removed = mutedPlayers.remove(target.getUniqueId());
		if (removed == null) {
			return new ModerationResult(false, "<yellow>ℹ Người chơi <white>" + escape(target.getUsername()) + "</white> chưa bị mute.</yellow>");
		}

		sendInfo(target, "<green>✔ Bạn đã được gỡ mute chat toàn mạng.</green>");
		return new ModerationResult(true, "<green>✔ Đã unmute <white>" + escape(target.getUsername()) + "</white>.</green>");
	}

	public ModerationResult muteStatusByName(String targetName) {
		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			return new ModerationResult(false, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>");
		}

		MuteRecord record = mutedPlayers.get(target.getUniqueId());
		if (record == null) {
			return new ModerationResult(true, "<green>✔ Người chơi <white>" + escape(target.getUsername()) + "</white> hiện không bị mute.</green>");
		}

		if (record.expiresAtEpochMs() != null) {
			long now = System.currentTimeMillis();
			if (now >= record.expiresAtEpochMs()) {
				mutedPlayers.remove(target.getUniqueId());
				return new ModerationResult(true, "<green>✔ Người chơi <white>" + escape(target.getUsername()) + "</white> hiện không bị mute.</green>");
			}
			long remaining = record.expiresAtEpochMs() - now;
			return new ModerationResult(true,
				"<yellow>ℹ <white>" + escape(target.getUsername()) + "</white> đang bị mute. Còn lại: <white>" + formatDuration(remaining)
					+ "</white>. Lý do: <white>" + escape(record.reason()) + "</white></yellow>");
		}

		return new ModerationResult(true,
			"<yellow>ℹ <white>" + escape(target.getUsername()) + "</white> đang bị mute vĩnh viễn. Lý do: <white>" + escape(record.reason()) + "</white></yellow>");
	}

	public ModerationResult warnByName(String actor, String targetName, String reason) {
		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			return new ModerationResult(false, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>");
		}

		sendError(target, "<yellow>⚠ Cảnh báo về ngôn ngữ chat: <white>" + escape(reason) + "</white></yellow>");
		logger.audit("WARN actor=" + actor + " target=" + target.getUsername() + " reason=" + reason);
		return new ModerationResult(true, "<green>✔ Đã cảnh báo <white>" + escape(target.getUsername()) + "</white>.</green>");
	}

	public ModerationResult broadcast(String actor, String message) {
		if (message == null || message.isBlank()) {
			return new ModerationResult(false, "<red>❌ Nội dung thông báo không được để trống.</red>");
		}

		VelocityMessengerConfig.FormatProfile profile = config.profileForServer("proxy");
		String serverColor = config.serverColor("");
		String rendered = renderWithStack(
			profile.broadcastFormat(),
			Map.of(
				"sender_name", actor == null || actor.isBlank() ? "Console" : actor,
				"server_name", "proxy",
				"server_display", stripLegacy(config.serverDisplay("proxy")),
				"channel_name", "broadcast",
				"server_color", serverColor
			),
			Map.of(),
			null
		);
		rendered = injectUnescapedPlaceholders(rendered, Map.of("message", message));
		rendered = injectUnescapedPlaceholders(rendered, Map.of(
			"player_display", actor == null || actor.isBlank() ? "Console" : actor,
			"sender_display", actor == null || actor.isBlank() ? "Console" : actor,
			"receiver_display", ""
		));

		int sent = 0;
		for (Player online : proxyServer.getAllPlayers()) {
			sendResult(online, MessengerResultType.NETWORK_CHAT, rendered, null);
			sent++;
		}

		logger.audit("CHAT[BROADCAST] " + toConsoleText(rendered));
		logger.audit("BROADCAST actor=" + actor + " recipients=" + sent + " message=" + message);
		return new ModerationResult(true, "<green>✔ Đã phát thông báo đến <white>" + sent + "</white> người chơi.</green>");
	}

	public ModerationResult enableSpyAll(UUID watcherId) {
		if (watcherId == null) {
			return new ModerationResult(false, "<red>❌ Không thể bật spy: watcher không hợp lệ.</red>");
		}

		spySubscriptions.put(watcherId, SpySubscription.all());
		return new ModerationResult(true, "<green>✔ Đã bật spy cho <white>toàn bộ người chơi</white>.</green>");
	}

	public ModerationResult enableSpyTarget(UUID watcherId, String targetName) {
		if (watcherId == null) {
			return new ModerationResult(false, "<red>❌ Không thể bật spy: watcher không hợp lệ.</red>");
		}

		Player target = proxyServer.getPlayer(targetName).orElse(null);
		if (target == null) {
			return new ModerationResult(false, "<red>❌ Không tìm thấy người chơi <white>" + escape(targetName) + "</white>.</red>");
		}

		spySubscriptions.put(watcherId, SpySubscription.player(target.getUniqueId(), target.getUsername()));
		return new ModerationResult(true, "<green>✔ Đã bật spy cho người chơi <white>" + escape(target.getUsername()) + "</white>.</green>");
	}

	public ModerationResult disableSpy(UUID watcherId) {
		if (watcherId == null) {
			return new ModerationResult(false, "<red>❌ Không thể tắt spy: watcher không hợp lệ.</red>");
		}

		SpySubscription removed = spySubscriptions.remove(watcherId);
		if (removed == null) {
			return new ModerationResult(false, "<yellow>ℹ Bạn chưa bật spy trước đó.</yellow>");
		}

		return new ModerationResult(true, "<green>✔ Đã tắt chế độ spy.</green>");
	}

	public ModerationResult spyStatus(UUID watcherId) {
		if (watcherId == null) {
			return new ModerationResult(false, "<red>❌ Không thể xem trạng thái spy: watcher không hợp lệ.</red>");
		}

		SpySubscription subscription = spySubscriptions.get(watcherId);
		if (subscription == null) {
			return new ModerationResult(true, "<yellow>ℹ Spy hiện đang <white>tắt</white>.</yellow>");
		}

		if (subscription.allPlayers()) {
			return new ModerationResult(true, "<green>✔ Spy đang theo dõi <white>toàn bộ người chơi</white>.</green>");
		}

		return new ModerationResult(true, "<green>✔ Spy đang theo dõi người chơi <white>" + escape(subscription.targetPlayerName()) + "</white>.</green>");
	}

	private void sendInfo(Player player, String message) {
		sendResult(player, MessengerResultType.INFO, message, null);
	}

	private void sendInfo(Player player, String message, UUID correlationId) {
		sendResult(player, MessengerResultType.INFO, message, correlationId);
	}

	private void sendError(Player player, String message) {
		sendResult(player, MessengerResultType.ERROR, message, null);
	}

	private void sendError(Player player, String message, UUID correlationId) {
		sendResult(player, MessengerResultType.ERROR, message, correlationId);
	}

	private void sendResult(Player player, MessengerResultType resultType, String message, UUID correlationId) {
		sendResult(player, resultType, message, correlationId, Map.of());
	}

	private void sendResult(Player player, MessengerResultType resultType, String message, UUID correlationId, Map<String, String> metadata) {
		sendResultIfConnected(player, resultType, message, correlationId, metadata);
	}

	private boolean sendResultIfConnected(Player player, MessengerResultType resultType, String message, UUID correlationId, Map<String, String> metadata) {
		ServerConnection connection = player.getCurrentServer().orElse(null);
		if (connection == null) {
			logger.debug("Bỏ qua gửi kết quả vì người chơi không có backend server: " + player.getUsername());
			return false;
		}

		MessengerResultMessage result = new MessengerResultMessage(
			MessengerResultMessage.CURRENT_PROTOCOL,
			correlationId,
			player.getUniqueId(),
			resultType,
			message,
			metadata
		);
		PluginMessageWriter writer = PluginMessageWriter.create();
		result.writeTo(writer);
		bus.send(connection, MessengerChannels.RESULT, writer.toByteArray());
		if (correlationId != null) {
			logger.audit("TX result=" + resultType.name() + " reqId=" + correlationId + " to=" + player.getUsername());
		}

		return true;
	}

	private void sendPresenceUpdate(MessengerPresenceMessage presenceMessage) {
		PluginMessageWriter writer = PluginMessageWriter.create();
		presenceMessage.writeTo(writer);
		byte[] payload = writer.toByteArray();

		Set<String> sentServers = new HashSet<>();
		for (Player online : proxyServer.getAllPlayers()) {
			ServerConnection connection = online.getCurrentServer().orElse(null);
			if (connection == null) {
				continue;
			}

			String serverName = connection.getServerInfo().getName();
			if (!sentServers.add(serverName)) {
				continue;
			}

			bus.send(connection, MessengerChannels.PRESENCE, payload);
		}
	}

	private String escape(String value) {
		return MM.escapeTags(value == null ? "" : value);
	}

	private String render(String template, Map<String, String> values) {
		return renderer.renderTemplate(template, values);
	}

	private String renderRaw(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		Map<String, String> safeValues = values == null ? Map.of() : values;
		for (Map.Entry<String, String> entry : safeValues.entrySet()) {
			String key = entry.getKey();
			String replacement = entry.getValue() == null ? "" : entry.getValue();
			output = output.replace("%" + key + "%", replacement);
		}
		return output;
	}

	private String renderWithStack(String template, Map<String, String> internalValues, Map<String, String> backendValues, Player player) {
		String internalRendered = render(template, internalValues == null ? Map.of() : internalValues);
		String miniRendered = miniPlaceholderResolver.resolve(player, internalRendered);
		return renderRaw(miniRendered, backendValues == null ? Map.of() : backendValues);
	}

	private void publishDiscord(
		VelocityMessengerConfig.MessageRouteConfig route,
		Map<String, String> internalValues,
		Map<String, String> backendValues,
		Player player,
		DiscordOutboundMessage.DispatchType dispatchType
	) {
		if (!config.discord().enabled() || !route.enabled()) {
			return;
		}

		Map<String, String> mergedInternalValues = withDiscordPlayerValues(internalValues, backendValues, player);
		Map<String, String> safeInternalValues = normalizeDiscordPlaceholderValues(mergedInternalValues);
		Map<String, String> safeBackendValues = normalizeDiscordPlaceholderValues(backendValues);
		String username = sanitizeDiscordUsername(renderWithStack(config.discord().webhookUsernameFormat(), safeInternalValues, safeBackendValues, player), safeInternalValues);
		String avatarUrl = sanitizeDiscordUrl(renderWithStack(config.discord().avatarUrlFormat(), safeInternalValues, safeBackendValues, player));
		if (route.mode() == VelocityMessengerConfig.PayloadType.EMBED) {
			String author = sanitizeDiscordText(renderWithStack(route.embedAuthor(), safeInternalValues, safeBackendValues, player));
			String authorUrl = sanitizeDiscordUrl(renderWithStack(route.embedAuthorUrl(), safeInternalValues, safeBackendValues, player));
			String authorIconUrl = sanitizeDiscordUrl(renderWithStack(route.embedAuthorIconUrl(), safeInternalValues, safeBackendValues, player));
			String title = sanitizeDiscordText(renderWithStack(route.embedTitle(), safeInternalValues, safeBackendValues, player));
			String description = sanitizeDiscordText(renderWithStack(route.embedDescription(), safeInternalValues, safeBackendValues, player));
			recordOutboundDiscordFingerprint(username, title);
			recordOutboundDiscordFingerprint(username, description);
			DiscordOutboundMessage.Embed embed = new DiscordOutboundMessage.Embed(
				author,
				authorUrl,
				authorIconUrl,
				title,
				description,
				route.embedColor(),
				sanitizeDiscordUrl(renderWithStack(route.embedThumbnailUrl(), safeInternalValues, safeBackendValues, player)),
				sanitizeDiscordUrl(renderWithStack(route.embedImageUrl(), safeInternalValues, safeBackendValues, player))
			);
			discordBridge.publish(new DiscordOutboundMessage(dispatchType, username, avatarUrl, null, embed));
			return;
		}

		String contentTemplate = route.content();
		String content = renderWithStack(contentTemplate, safeInternalValues, safeBackendValues, player);
		recordOutboundDiscordFingerprint(username, content);
		discordBridge.publish(new DiscordOutboundMessage(dispatchType, username, avatarUrl, content, null));
	}

	private Map<String, String> normalizeDiscordPlaceholderValues(Map<String, String> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}

		Map<String, String> output = new HashMap<>();
		for (Map.Entry<String, String> entry : input.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (key != null && key.toLowerCase().contains("url")) {
				output.put(key, value == null ? "" : value);
				continue;
			}

			String normalized = normalizeDiscordPlaceholderValue(value);
			output.put(key, normalized);
		}

		return output;
	}

	private String normalizeDiscordPlaceholderValue(String value) {
		if (value == null) {
			return "";
		}

		return value.replace("\\", "");
	}

	private Map<String, String> withDiscordPlayerValues(Map<String, String> internalValues, Map<String, String> backendValues, Player player) {
		Map<String, String> output = new HashMap<>();
		if (internalValues != null) {
			output.putAll(internalValues);
		}

		String playerName = player == null ? output.getOrDefault("sender_name", "Minecraft") : player.getUsername();
		String playerPrefix = player == null ? output.getOrDefault("player_prefix", "") : resolvePresencePlayerPrefix(player);
		String playerDisplay = player == null
			? output.getOrDefault("sender_display", playerName)
			: resolvePlayerDisplay(player, playerPrefix, playerName, backendValues);
		String userUsername = output.getOrDefault("sender_name", playerName);
		String userNickname = output.getOrDefault("sender_display", playerDisplay);
		String userName = userUsername == null || userUsername.isBlank() ? playerName : userUsername;
		String serverName = output.getOrDefault("server_name", "");
		String serverDisplay = output.getOrDefault("server_display", "");
		if ((serverDisplay == null || serverDisplay.isBlank()) && !serverName.isBlank()) {
			serverDisplay = stripLegacy(config.serverDisplay(serverName));
		}

		output.put("player_name", playerName);
		output.put("sender_name", output.getOrDefault("sender_name", playerName));
		output.put("player_prefix", playerPrefix == null ? "" : playerPrefix);
		output.put("player_display", playerDisplay == null ? "" : playerDisplay);
		output.put("sender_display", output.getOrDefault("sender_display", playerDisplay));
		output.put("server_display", serverDisplay == null ? "" : serverDisplay);
		output.put("user_name", output.getOrDefault("user_name", userName == null ? "" : userName));
		output.put("user_username", output.getOrDefault("user_username", userUsername == null ? "" : userUsername));
		output.put("user_nickname", output.getOrDefault("user_nickname", userNickname == null ? "" : userNickname));
		return output;
	}

	private void recordOutboundDiscordFingerprint(String author, String content) {
		if (content == null || content.isBlank()) {
			return;
		}

		long now = System.currentTimeMillis();
		int loopWindowMs = DISCORD_LOOP_WINDOW_MS;
		recentDiscordOutboundFingerprints.entrySet().removeIf(entry -> now - entry.getValue() > loopWindowMs);
		recentDiscordOutboundFingerprints.put(fingerprint(author, content), now);

		while (recentDiscordOutboundFingerprints.size() > MAX_DISCORD_FINGERPRINTS) {
			String oldestKey = null;
			long oldestAt = Long.MAX_VALUE;
			for (Map.Entry<String, Long> entry : recentDiscordOutboundFingerprints.entrySet()) {
				Long value = entry.getValue();
				if (value == null || value >= oldestAt) {
					continue;
				}

				oldestAt = value;
				oldestKey = entry.getKey();
			}

			if (oldestKey == null) {
				break;
			}

			recentDiscordOutboundFingerprints.remove(oldestKey);
		}
	}

	private boolean shouldDropInboundDiscordLoop(String author, String content, String source) {
		String key = fingerprint(author, content);
		Long outboundAt = recentDiscordOutboundFingerprints.get(key);
		if (outboundAt == null) {
			return false;
		}

		long now = System.currentTimeMillis();
		int loopWindowMs = DISCORD_LOOP_WINDOW_MS;
		return now - outboundAt <= loopWindowMs;
	}

	private String fingerprint(String author, String content) {
		String left = author == null ? "" : author.trim().toLowerCase();
		String right = content == null ? "" : content.trim().toLowerCase();
		return left + "|" + right;
	}

	private String resolvePresencePlayerPrefix(Player player) {
		if (player == null) {
			return "";
		}

		try {
			return luckPermsService.getPlayerPrefix(player.getUniqueId());
		} catch (Throwable throwable) {
			logger.debug("Không thể lấy prefix từ LuckPerms cho " + player.getUsername() + ": " + throwable.getMessage());
			return "";
		}
	}

	private String resolvePlayerDisplay(Player player, String playerPrefix, String fallbackDisplayName, Map<String, String> backendValues) {
		String displayName = fallbackDisplayName == null ? "" : fallbackDisplayName;
		String format = config.userDisplayFormat();
		String internalRendered = renderRaw(format, Map.of(
			"player_prefix", playerPrefix == null ? "" : playerPrefix,
			"displayname", displayName,
			"player_name", displayName,
			"sender_name", displayName,
			"target_name", displayName,
			"receiver_name", displayName
		));
		String miniRendered = miniPlaceholderResolver.resolve(player, internalRendered);
		String rendered = renderRaw(miniRendered, backendValues == null ? Map.of() : backendValues);
		String normalized = rendered == null ? "" : rendered.trim();
		return normalized.isEmpty() ? displayName : normalized;
	}

	private String resolveAvatarUrl(Player player, Map<String, String> resolvedValues) {
		Map<String, String> internalValues = Map.of(
			"sender_name", player.getUsername(),
			"player_name", player.getUsername()
		);
		return renderWithStack(config.discord().avatarUrlFormat(), internalValues, resolvedValues, player);
	}

	private String sanitizeDiscordUsername(String rawUsername, Map<String, String> internalValues) {
		String sanitized = sanitizeDiscordText(rawUsername);
		sanitized = sanitized.replace("\\", "").trim();

		if (sanitized.isBlank()) {
			sanitized = internalValues.getOrDefault("sender_name", "Minecraft").trim();
		}

		if (sanitized.length() > 80) {
			sanitized = sanitized.substring(0, 80);
		}

		return sanitized;
	}

	private String sanitizeDiscordText(String value) {
		String sanitized = value == null ? "" : value;
		sanitized = MINI_TAG_PATTERN.matcher(sanitized).replaceAll("");
		sanitized = LEGACY_CODE_PATTERN.matcher(sanitized).replaceAll("");
		sanitized = stripLegacy(sanitized);
		return sanitized.replaceAll("\\s+", " ").trim();
	}

	private String normalizeDiscordDisplayToken(String value) {
		String sanitized = sanitizeDiscordText(value);
		return sanitized.replace("\\", "").trim();
	}

	private String sanitizeDiscordUrl(String value) {
		if (value == null) {
			return "";
		}
		String sanitized = MINI_TAG_PATTERN.matcher(value).replaceAll("");
		sanitized = LEGACY_CODE_PATTERN.matcher(sanitized).replaceAll("");
		return sanitized.trim();
	}

	private String renderPlayerMessage(
		String template,
		String rawMessage,
		Map<String, String> internalValues,
		Map<String, String> backendValues,
		Player player,
		Map<String, String> unescapedValues
	) {
		Map<String, String> unescapedMap = unescapedValues == null ? Map.of() : unescapedValues;
		String rendered = render(template, internalValues == null ? Map.of() : internalValues);
		rendered = injectUnescapedPlaceholders(rendered, unescapedMap);
		rendered = miniPlaceholderResolver.resolve(player, rendered);
		rendered = renderRaw(rendered, backendValues == null ? Map.of() : backendValues);
		// Safeguard: backend-resolved values may still leave display placeholders unchanged.
		rendered = injectUnescapedPlaceholders(rendered, unescapedMap);
		String messageMini = legacyToMini(rawMessage);
		if (rendered.contains("%message%")) {
			return rendered.replace("%message%", messageMini);
		}

		if (messageMini.isBlank()) {
			return rendered;
		}

		if (rendered.isBlank()) {
			return messageMini;
		}

		return rendered + " " + messageMini;
	}

	private String injectUnescapedPlaceholders(String rendered, Map<String, String> values) {
		String output = rendered;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String value = entry.getValue() == null ? "" : entry.getValue();
			output = output.replace("%" + entry.getKey() + "%", value);
		}
		return output;
	}

	private String legacyToMini(String input) {
		Component component = LEGACY.deserialize(normalizeLegacyHex(input));
		return MM.serialize(component);
	}

	private String stripLegacy(String input) {
		Component component = LEGACY.deserialize(normalizeLegacyHex(input));
		return PLAIN.serialize(component);
	}

	private String toConsoleText(String input) {
		String text = input == null ? "" : input;
		text = MINI_TAG_PATTERN.matcher(text).replaceAll("");
		text = LEGACY_CODE_PATTERN.matcher(text).replaceAll("");
		text = stripLegacy(text);
		return text.replaceAll("\\s+", " ").trim();
	}

	private String normalizeLegacyHex(String input) {
		if (input == null || input.isBlank()) {
			return "";
		}

		Matcher matcher = LEGACY_HEX_PATTERN.matcher(input);
		StringBuffer out = new StringBuffer();
		while (matcher.find()) {
			String hex = matcher.group(1).toLowerCase();
			String replacement = "&x"
				+ "&" + hex.charAt(0)
				+ "&" + hex.charAt(1)
				+ "&" + hex.charAt(2)
				+ "&" + hex.charAt(3)
				+ "&" + hex.charAt(4)
				+ "&" + hex.charAt(5);
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private MentionProcessing processMentions(Player sender, String rawMessage, List<Player> recipients) {
		VelocityMessengerConfig.MentionConfig mentionConfig = config.mentions();
		if (!mentionConfig.enabled() || rawMessage == null || rawMessage.isBlank()) {
			return new MentionProcessing(rawMessage == null ? "" : rawMessage, Set.of());
		}

		Map<String, Player> candidates = new HashMap<>();
		for (Player player : recipients) {
			candidates.putIfAbsent(player.getUsername().toLowerCase(), player);
		}

		Set<UUID> mentionedById = new HashSet<>();
		Matcher matcher = MENTION_PATTERN.matcher(rawMessage);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String candidateName = matcher.group(1);
			Player target = candidates.get(candidateName.toLowerCase());
			if (target == null) {
				continue;
			}

			if (mentionConfig.exactUsernameOnly() && !target.getUsername().equalsIgnoreCase(candidateName)) {
				continue;
			}

			String replacement = render(mentionConfig.highlightFormat(), Map.of("name", target.getUsername()));
			matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
			if (!target.getUniqueId().equals(sender.getUniqueId())) {
				mentionedById.add(target.getUniqueId());
			}
		}
		matcher.appendTail(output);

		return new MentionProcessing(output.toString(), Set.copyOf(mentionedById));
	}

	private void notifyMentionTarget(Player sender, Player recipient, Set<UUID> mentionedById) {
		if (!mentionedById.contains(recipient.getUniqueId())) {
			return;
		}

		VelocityMessengerConfig.MentionConfig mentionConfig = config.mentions();
		String alert = render(mentionConfig.alertFormat(), Map.of(
			"sender_name", sender.getUsername(),
			"target_name", recipient.getUsername()
		));
		Map<String, String> metadata = new HashMap<>();
		metadata.put("toast.enabled", String.valueOf(mentionConfig.toastEnabled()));
		metadata.put("toast.title", render(mentionConfig.toastTitle(), Map.of(
			"sender_name", sender.getUsername(),
			"target_name", recipient.getUsername()
		)));
		metadata.put("toast.subtitle", render(mentionConfig.toastSubtitle(), Map.of(
			"sender_name", sender.getUsername(),
			"target_name", recipient.getUsername()
		)));
		metadata.put("toast.fade-in-ms", String.valueOf(mentionConfig.toastFadeInMs()));
		metadata.put("toast.stay-ms", String.valueOf(mentionConfig.toastStayMs()));
		metadata.put("toast.fade-out-ms", String.valueOf(mentionConfig.toastFadeOutMs()));
		metadata.put("mention.sound", mentionConfig.sound() == null ? "" : mentionConfig.sound());
		sendResult(recipient, MessengerResultType.MENTION_ALERT, alert, null, metadata);
		playMentionSound(recipient, mentionConfig.sound());
	}

	private void playMentionSound(Player recipient, String soundName) {
		if (soundName == null || soundName.isBlank() || soundName.equalsIgnoreCase("none")) {
			return;
		}

		try {
			String normalized = soundName.trim().toLowerCase();
			if (!normalized.contains(":")) {
				normalized = normalized.replace('_', '.');
			}
			if (!normalized.contains(":")) {
				normalized = "minecraft:" + normalized;
			}
			recipient.playSound(Sound.sound(Key.key(normalized), Sound.Source.PLAYER, 1f, 1f));
		} catch (Exception exception) {
			logger.debug("Không thể phát sound mention cho " + recipient.getUsername() + ": " + soundName);
		}
	}

	private boolean checkMuted(Player sender, UUID correlationId) {
		MuteRecord record = mutedPlayers.get(sender.getUniqueId());
		if (record == null) {
			return false;
		}

		if (record.expiresAtEpochMs() != null) {
			long now = System.currentTimeMillis();
			if (now >= record.expiresAtEpochMs()) {
				mutedPlayers.remove(sender.getUniqueId());
				return false;
			}

			long remaining = record.expiresAtEpochMs() - now;
			sendError(sender, "<red>❌ Bạn đang bị mute chat toàn mạng. Còn lại: <white>" + formatDuration(remaining) + "</white>. Lý do: <white>" + escape(record.reason()) + "</white></red>", correlationId);
			return true;
		}

		sendError(sender, "<red>❌ Bạn đang bị mute chat toàn mạng. Lý do: <white>" + escape(record.reason()) + "</white></red>", correlationId);
		return true;
	}

	private boolean checkRateLimit(Player sender, UUID correlationId) {
		VelocityMessengerConfig.RateLimitConfig rateLimit = config.rateLimit();
		if (!rateLimit.enabled()) {
			return false;
		}

		String bypass = rateLimit.bypassPermission();
		if (bypass != null && !bypass.isBlank() && sender.hasPermission(bypass)) {
			return false;
		}

		long now = System.currentTimeMillis();
		int cooldownMs = Math.max(0, rateLimit.cooldownMs() == null ? 0 : rateLimit.cooldownMs());
		int windowMs = Math.max(1000, rateLimit.windowMs() == null ? 5000 : rateLimit.windowMs());
		int maxMessages = Math.max(1, rateLimit.maxMessages() == null ? 6 : rateLimit.maxMessages());

		RateLimitState state = rateLimitStates.computeIfAbsent(sender.getUniqueId(), ignored -> new RateLimitState());
		synchronized (state) {
			if (cooldownMs > 0 && state.lastMessageAt > 0) {
				long diff = now - state.lastMessageAt;
				if (diff < cooldownMs) {
					sendError(sender,
						"<red>❌ Bạn gửi chat quá nhanh. Hãy chờ <white>" + formatDuration(cooldownMs - diff) + "</white>.</red>",
						correlationId
					);
					return true;
				}
			}

			while (!state.window.isEmpty() && now - state.window.peekFirst() > windowMs) {
				state.window.removeFirst();
			}
			if (state.window.size() >= maxMessages) {
				sendError(sender,
					"<red>❌ Bạn đang chat quá nhiều trong thời gian ngắn. Vui lòng chậm lại.</red>",
					correlationId
				);
				return true;
			}

			state.window.addLast(now);
			state.lastMessageAt = now;
		}
		return false;
	}

	private String formatDuration(long millis) {
		long totalSeconds = Math.max(1L, millis / 1000L);
		long days = totalSeconds / 86400L;
		totalSeconds %= 86400L;
		long hours = totalSeconds / 3600L;
		totalSeconds %= 3600L;
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;

		StringBuilder builder = new StringBuilder();
		if (days > 0) {
			builder.append(days).append("d ");
		}
		if (hours > 0) {
			builder.append(hours).append("h ");
		}
		if (minutes > 0) {
			builder.append(minutes).append("m ");
		}
		if (seconds > 0 || builder.isEmpty()) {
			builder.append(seconds).append("s");
		}
		return builder.toString().trim();
	}

	private record MuteRecord(String actor, String reason, long mutedAtEpochMs, Long expiresAtEpochMs) {
	}

	private record SpySubscription(boolean allPlayers, UUID targetPlayerId, String targetPlayerName) {
		private static SpySubscription all() {
			return new SpySubscription(true, null, "");
		}

		private static SpySubscription player(UUID targetPlayerId, String targetPlayerName) {
			return new SpySubscription(false, targetPlayerId, targetPlayerName == null ? "" : targetPlayerName);
		}

		private boolean matches(UUID senderId, UUID receiverId) {
			if (allPlayers) {
				return true;
			}
			if (targetPlayerId == null) {
				return false;
			}
			return targetPlayerId.equals(senderId) || targetPlayerId.equals(receiverId);
		}
	}

	private static final class RateLimitState {
		private long lastMessageAt;
		private final ArrayDeque<Long> window;

		private RateLimitState() {
			this.lastMessageAt = 0L;
			this.window = new ArrayDeque<>();
		}
	}

	private record PokeStreakKey(UUID senderId, UUID targetId) {
	}

	private record PokeStreakState(int streak, long lastPokeAtEpochMs) {
	}

	public record PersistedContext(String type, UUID directTargetId, String directTargetName) {
	}

	public record PersistedMute(String actor, String reason, long mutedAtEpochMs, Long expiresAtEpochMs) {
	}

	public record PersistentState(
		Map<UUID, PersistedContext> contexts,
		Map<UUID, UUID> lastReplyByPlayer,
		Map<UUID, PersistedMute> mutedPlayers,
		Set<UUID> seenPlayers
	) {
		public PersistentState {
			contexts = contexts == null ? Map.of() : Map.copyOf(contexts);
			lastReplyByPlayer = lastReplyByPlayer == null ? Map.of() : Map.copyOf(lastReplyByPlayer);
			mutedPlayers = mutedPlayers == null ? Map.of() : Map.copyOf(mutedPlayers);
			seenPlayers = seenPlayers == null ? Set.of() : Set.copyOf(seenPlayers);
		}
	}

	public record ModerationResult(boolean success, String message) {
	}

	private record MentionProcessing(String renderedMessage, Set<UUID> mentionedById) {
	}
}
