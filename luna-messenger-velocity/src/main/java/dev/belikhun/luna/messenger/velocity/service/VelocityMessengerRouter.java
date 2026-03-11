package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessagingContext;
import dev.belikhun.luna.core.api.messenger.MessagingContextType;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.ProxyMessageTemplateRenderer;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.key.Key;
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
	private volatile DiscordBridgeGateway discordBridge;
	private final Map<UUID, MessagingContext> contextByPlayer;
	private final Map<UUID, UUID> lastReplyByPlayer;
	private final Map<UUID, MuteRecord> mutedPlayers;
	private final Map<UUID, RateLimitState> rateLimitStates;
	private final Set<UUID> seenPlayers;
	private final Map<String, Long> recentDiscordOutboundFingerprints;

	public VelocityMessengerRouter(
		ProxyServer proxyServer,
		LunaLogger logger,
		PluginMessageBus<Object, Object> bus,
		VelocityMessengerConfig config,
		ProxyMessageTemplateRenderer renderer,
		DiscordBridgeGateway discordBridge
	) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Router");
		this.bus = bus;
		this.config = config;
		this.renderer = renderer;
		this.miniPlaceholderResolver = new VelocityMiniPlaceholderResolver();
		this.discordBridge = discordBridge;
		this.contextByPlayer = new ConcurrentHashMap<>();
		this.lastReplyByPlayer = new ConcurrentHashMap<>();
		this.mutedPlayers = new ConcurrentHashMap<>();
		this.rateLimitStates = new ConcurrentHashMap<>();
		this.seenPlayers = ConcurrentHashMap.newKeySet();
		this.recentDiscordOutboundFingerprints = new ConcurrentHashMap<>();
	}

	public void registerChannels() {
		bus.registerIncoming(MessengerChannels.COMMAND, context -> {
			MessengerCommandRequest request = MessengerCommandRequest.readFrom(context.reader());
			handle(request);
			return PluginMessageDispatchResult.HANDLED;
		});
		bus.registerOutgoing(MessengerChannels.RESULT);
	}

	public void close() {
		bus.unregisterIncoming(MessengerChannels.COMMAND);
		bus.unregisterOutgoing(MessengerChannels.RESULT);
		contextByPlayer.clear();
		lastReplyByPlayer.clear();
		mutedPlayers.clear();
		rateLimitStates.clear();
		seenPlayers.clear();
		recentDiscordOutboundFingerprints.clear();
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
	}

	public void reloadRuntime(VelocityMessengerConfig newConfig, DiscordBridgeGateway newDiscordBridge) {
		this.config = newConfig;
		this.discordBridge = newDiscordBridge;
		logger.audit("Đã cập nhật runtime config cho Messenger router.");
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
			case SEND_CHAT -> routeChat(sender, request.argument(), request.resolvedValues(), correlationId);
			case SEND_REPLY -> handleReply(sender, request.argument(), request.resolvedValues(), correlationId);
		}
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

		sendDirect(sender, target, message, resolvedValues, correlationId);
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
			sendDirect(sender, target, message, resolvedValues, correlationId);
			return;
		}
		routeNetwork(sender, message, resolvedValues, correlationId);
	}

	private void routeNetwork(Player sender, String message, Map<String, String> resolvedValues, UUID correlationId) {
		String serverName = sender.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		String serverDisplay = config.serverDisplay(serverName);
		String serverColor = config.serverColor(serverName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String channelName = config.discord().networkChannelName();
		String avatarUrl = resolveAvatarUrl(sender, resolvedValues);
		List<Player> recipients = List.copyOf(proxyServer.getAllPlayers());
		MentionProcessing mention = processMentions(sender, message, recipients);
		Map<String, String> internalValues = Map.of(
			"sender_name", sender.getUsername(),
			"server_name", serverName,
			"channel_name", channelName,
			"player_avatar_url", avatarUrl,
			"server_color", serverColor
		);
		String rendered = renderPlayerMessage(profile.networkFormat(), mention.renderedMessage(), internalValues, resolvedValues, sender, Map.of(
			"server_display", serverDisplay
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
			"server_display", stripLegacy(serverDisplay),
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
			"channel_name", "server",
			"player_avatar_url", resolveAvatarUrl(sender, resolvedValues),
			"server_color", serverColor
		), resolvedValues, sender, Map.of(
			"server_display", serverDisplay
		));
		for (Player player : recipients) {
			sendResult(player, MessengerResultType.SERVER_CHAT, rendered, correlationId);
			notifyMentionTarget(sender, player, mention.mentionedById());
		}
		logger.audit("CHAT[SERVER:" + serverName + "] " + toConsoleText(rendered));
	}

	private void sendDirect(Player sender, Player target, String message, Map<String, String> resolvedValues, UUID correlationId) {
		String senderServer = sender.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		String senderServerDisplay = config.serverDisplay(senderServer);
		String senderServerColor = config.serverColor(senderServer);
		String senderPrefix = resolvePresencePlayerPrefix(sender);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(senderServer);
		String toSender = renderPlayerMessage(profile.directToSenderFormat(), message, Map.of(
			"sender_name", sender.getUsername(),
			"target_name", target.getUsername(),
			"player_prefix", senderPrefix,
			"server_name", senderServer,
			"channel_name", "direct",
			"player_avatar_url", resolveAvatarUrl(sender, resolvedValues),
			"server_color", senderServerColor
		), resolvedValues, sender, Map.of(
			"server_display", senderServerDisplay
		));
		String toTarget = renderPlayerMessage(profile.directToReceiverFormat(), message, Map.of(
			"sender_name", sender.getUsername(),
			"target_name", target.getUsername(),
			"player_prefix", senderPrefix,
			"server_name", senderServer,
			"channel_name", "direct",
			"player_avatar_url", resolveAvatarUrl(target, resolvedValues),
			"server_color", senderServerColor
		), resolvedValues, sender, Map.of(
			"server_display", senderServerDisplay
		));
		sendResult(sender, MessengerResultType.DIRECT_ECHO, toSender, correlationId);
		sendResult(target, MessengerResultType.DIRECT_CHAT, toTarget, correlationId);
		logger.audit("CHAT[DIRECT] " + sender.getUsername() + " -> " + target.getUsername() + ": " + toConsoleText(toSender));
		lastReplyByPlayer.put(sender.getUniqueId(), target.getUniqueId());
		lastReplyByPlayer.put(target.getUniqueId(), sender.getUniqueId());
	}

	public void routeInboundDiscordMessage(DiscordInboundMessage inboundMessage) {
		if (inboundMessage == null) {
			return;
		}

		String source = inboundMessage.source() == null ? "discord" : inboundMessage.source();
		String authorName = inboundMessage.authorName() == null ? "Discord" : inboundMessage.authorName();
		String message = inboundMessage.message() == null ? "" : inboundMessage.message();
		if (shouldDropInboundDiscordLoop(authorName, message, source)) {
			logger.audit("Bỏ qua Discord inbound do anti-loop: source=" + source + ", author=" + authorName);
			return;
		}

		// Discord -> network mặc định theo yêu cầu.
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer("");
		String channelName = config.discord().networkChannelName();
		String serverColor = config.serverColor("");
		String rendered = renderWithStack(profile.discordInboundNetworkFormat(), Map.of(
			"discord_author", authorName,
			"channel_name", channelName,
			"message", message,
			"server_color", serverColor,
			"discord_source", source,
			"discord_message_id", inboundMessage.messageId() == null ? "" : inboundMessage.messageId(),
			"discord_author_id", inboundMessage.authorId() == null ? "" : inboundMessage.authorId()
		), Map.of(), null);
		for (Player player : proxyServer.getAllPlayers()) {
			sendResult(player, MessengerResultType.NETWORK_CHAT, rendered, null);
		}
	}

	public void handlePlayerJoin(Player player, String connectedServerName) {
		boolean firstJoin = seenPlayers.add(player.getUniqueId());
		String serverName = (connectedServerName == null || connectedServerName.isBlank())
			? player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("proxy")
			: connectedServerName;
		String serverColor = config.serverColor(serverName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String joinTemplate = firstJoin ? profile.firstJoinNetworkFormat() : profile.joinNetworkFormat();
		String joinRendered = renderWithStack(
			joinTemplate,
			Map.ofEntries(
				Map.entry("sender_name", player.getUsername()),
				Map.entry("player_name", player.getUsername()),
				Map.entry("displayname", player.getUsername()),
				Map.entry("player_prefix", resolvePresencePlayerPrefix(player)),
				Map.entry("server_name", serverName),
				Map.entry("server_display", config.serverDisplay(serverName)),
				Map.entry("channel_name", config.discord().networkChannelName()),
				Map.entry("server_color", serverColor),
				Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
				Map.entry("presence_type", firstJoin ? "FIRST_JOIN" : "JOIN"),
				Map.entry("message", firstJoin
					? player.getUsername() + " đã vào mạng lần đầu"
					: player.getUsername() + " đã vào mạng")
			),
			Map.of(),
			player
		);
		for (Player online : proxyServer.getAllPlayers()) {
			sendResult(online, MessengerResultType.NETWORK_CHAT, joinRendered, null);
		}

		publishDiscord(config.discord().joinMessage(), Map.of(
			"sender_name", player.getUsername(),
			"server_name", serverName,
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
		lastReplyByPlayer.entrySet().removeIf(entry -> entry.getValue().equals(leavingId));
		contextByPlayer.entrySet().removeIf(entry -> {
			MessagingContext context = entry.getValue();
			return context != null && context.type() == MessagingContextType.DIRECT && leavingId.equals(context.directTargetId());
		});

		String serverName = (previousServerName == null || previousServerName.isBlank())
			? player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("")
			: previousServerName;
		String serverColor = config.serverColor(serverName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(serverName);
		String leaveRendered = renderWithStack(
			profile.leaveNetworkFormat(),
			Map.ofEntries(
				Map.entry("sender_name", player.getUsername()),
				Map.entry("player_name", player.getUsername()),
				Map.entry("displayname", player.getUsername()),
				Map.entry("player_prefix", resolvePresencePlayerPrefix(player)),
				Map.entry("server_name", serverName),
				Map.entry("server_display", config.serverDisplay(serverName)),
				Map.entry("channel_name", config.discord().networkChannelName()),
				Map.entry("server_color", serverColor),
				Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
				Map.entry("presence_type", "LEAVE"),
				Map.entry("message", player.getUsername() + " đã rời mạng")
			),
			Map.of(),
			player
		);
		for (Player online : proxyServer.getAllPlayers()) {
			sendResult(online, MessengerResultType.NETWORK_CHAT, leaveRendered, null);
		}

		publishDiscord(config.discord().leaveMessage(), Map.of(
			"sender_name", player.getUsername(),
			"server_name", "",
			"channel_name", config.discord().networkChannelName(),
			"server_color", serverColor,
			"player_avatar_url", resolveAvatarUrl(player, Map.of()),
			"message", player.getUsername() + " đã rời mạng"
		), Map.of(), player, DiscordOutboundMessage.DispatchType.BROADCAST);
	}

	public void handleServerSwitch(Player player, String previousServerName) {
		String toServerName = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		String toDisplay = config.serverDisplay(toServerName);
		String fromDisplay = config.serverDisplay(previousServerName);
		String toServerColor = config.serverColor(toServerName);
		VelocityMessengerConfig.FormatProfile profile = config.profileForServer(toServerName);
		if (!profile.serverSwitchEnabled()) {
			return;
		}

		String rendered = renderWithStack(profile.serverSwitchFormat(), Map.ofEntries(
			Map.entry("sender_name", player.getUsername()),
			Map.entry("displayname", player.getUsername()),
			Map.entry("player_prefix", resolvePresencePlayerPrefix(player)),
			Map.entry("from_server", previousServerName),
			Map.entry("to_server", toServerName),
			Map.entry("from", fromDisplay),
			Map.entry("to", toDisplay),
			Map.entry("from_clean", stripLegacy(fromDisplay)),
			Map.entry("to_clean", stripLegacy(toDisplay)),
			Map.entry("presence_type", "SWAP"),
			Map.entry("server_name", toServerName),
			Map.entry("server_color", toServerColor)
		), Map.of(), player);
		rendered = injectUnescapedPlaceholders(rendered, Map.of(
			"from_display", fromDisplay,
			"to_display", toDisplay,
			"server_display", toDisplay
		));

		for (Player online : proxyServer.getAllPlayers()) {
			sendResult(online, MessengerResultType.NETWORK_CHAT, rendered, null);
		}

		publishDiscord(config.discord().switchMessage(), Map.ofEntries(
			Map.entry("sender_name", player.getUsername()),
			Map.entry("from_server", previousServerName),
			Map.entry("to_server", toServerName),
			Map.entry("from_display", stripLegacy(fromDisplay)),
			Map.entry("to_display", stripLegacy(toDisplay)),
			Map.entry("server_name", toServerName),
			Map.entry("server_display", stripLegacy(toDisplay)),
			Map.entry("channel_name", config.discord().networkChannelName()),
			Map.entry("server_color", toServerColor),
			Map.entry("player_avatar_url", resolveAvatarUrl(player, Map.of())),
			Map.entry("presence_type", "SWAP"),
			Map.entry("message", player.getUsername() + " chuyển " + previousServerName + " -> " + toServerName)
		), Map.of(), player, DiscordOutboundMessage.DispatchType.BROADCAST);

		logger.debug("Switch server: " + player.getUsername() + " " + previousServerName + " -> " + toServerName);
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
				"channel_name", "broadcast",
				"server_color", serverColor,
				"message", message
			),
			Map.of(),
			null
		);

		int sent = 0;
		for (Player online : proxyServer.getAllPlayers()) {
			sendResult(online, MessengerResultType.NETWORK_CHAT, rendered, null);
			sent++;
		}

		logger.audit("CHAT[BROADCAST] " + toConsoleText(rendered));
		logger.audit("BROADCAST actor=" + actor + " recipients=" + sent + " message=" + message);
		return new ModerationResult(true, "<green>✔ Đã phát thông báo đến <white>" + sent + "</white> người chơi.</green>");
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
		ServerConnection connection = player.getCurrentServer().orElse(null);
		if (connection == null) {
			logger.debug("Bỏ qua gửi kết quả vì người chơi không có backend server: " + player.getUsername());
			return;
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
		String backendRendered = renderRaw(internalRendered, backendValues == null ? Map.of() : backendValues);
		return miniPlaceholderResolver.resolve(player, backendRendered);
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

		String username = sanitizeDiscordUsername(renderWithStack(config.discord().webhookUsernameFormat(), internalValues, backendValues, player), internalValues);
		String avatarUrl = renderWithStack(config.discord().avatarUrlFormat(), internalValues, backendValues, player);
		if (route.mode() == VelocityMessengerConfig.PayloadType.EMBED) {
			String title = renderWithStack(route.embedTitle(), internalValues, backendValues, player);
			String description = renderWithStack(route.embedDescription(), internalValues, backendValues, player);
			recordOutboundDiscordFingerprint(username, title);
			recordOutboundDiscordFingerprint(username, description);
			DiscordOutboundMessage.Embed embed = new DiscordOutboundMessage.Embed(
				title,
				description,
				route.embedColor(),
				renderWithStack(route.embedThumbnailUrl(), internalValues, backendValues, player),
				renderWithStack(route.embedImageUrl(), internalValues, backendValues, player)
			);
			discordBridge.publish(new DiscordOutboundMessage(dispatchType, username, avatarUrl, null, embed));
			return;
		}

		String contentTemplate = route.content();
		String content = renderWithStack(contentTemplate, internalValues, backendValues, player);
		recordOutboundDiscordFingerprint(username, content);
		discordBridge.publish(new DiscordOutboundMessage(dispatchType, username, avatarUrl, content, null));
	}

	private void recordOutboundDiscordFingerprint(String author, String content) {
		if (content == null || content.isBlank()) {
			return;
		}

		long now = System.currentTimeMillis();
		int loopWindowMs = DISCORD_LOOP_WINDOW_MS;
		recentDiscordOutboundFingerprints.entrySet().removeIf(entry -> now - entry.getValue() > loopWindowMs);
		recentDiscordOutboundFingerprints.put(fingerprint(author, content), now);
	}

	private boolean shouldDropInboundDiscordLoop(String author, String content, String source) {
		VelocityMessengerConfig.DiscordBotConfig botConfig = config.discord().bot();
		String selfSourceId = botConfig == null ? "" : botConfig.sourceId();
		if (selfSourceId != null && !selfSourceId.isBlank() && selfSourceId.equalsIgnoreCase(source)) {
			return true;
		}

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
		String raw = miniPlaceholderResolver.resolve(player, "<luckperms_prefix>");
		if (raw == null || raw.isBlank() || raw.equals("<luckperms_prefix>")) {
			return "";
		}
		return raw;
	}

	private String resolveAvatarUrl(Player player, Map<String, String> resolvedValues) {
		Map<String, String> internalValues = Map.of(
			"sender_name", player.getUsername(),
			"player_name", player.getUsername()
		);
		return renderWithStack(config.discord().avatarUrlFormat(), internalValues, resolvedValues, player);
	}

	private String sanitizeDiscordUsername(String rawUsername, Map<String, String> internalValues) {
		String sanitized = rawUsername == null ? "" : rawUsername;
		sanitized = MINI_TAG_PATTERN.matcher(sanitized).replaceAll("");
		sanitized = LEGACY_CODE_PATTERN.matcher(sanitized).replaceAll("");
		sanitized = stripLegacy(sanitized);
		sanitized = sanitized.replaceAll("\\s+", " ").trim();

		if (sanitized.isBlank()) {
			sanitized = internalValues.getOrDefault("sender_name", "Minecraft").trim();
		}

		if (sanitized.length() > 80) {
			sanitized = sanitized.substring(0, 80);
		}

		return sanitized;
	}

	private String renderPlayerMessage(
		String template,
		String rawMessage,
		Map<String, String> internalValues,
		Map<String, String> backendValues,
		Player player,
		Map<String, String> unescapedValues
	) {
		String rendered = renderWithStack(template, internalValues, backendValues, player);
		rendered = injectUnescapedPlaceholders(rendered, unescapedValues);
		String messageMini = legacyToMini(rawMessage);
		return rendered.replace("%message%", messageMini);
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
		sendResult(recipient, MessengerResultType.MENTION_ALERT, alert, null, metadata);
		playMentionSound(recipient, mentionConfig.sound());
	}

	private void playMentionSound(Player recipient, String soundName) {
		if (soundName == null || soundName.isBlank() || soundName.equalsIgnoreCase("none")) {
			return;
		}

		try {
			String normalized = soundName.toLowerCase();
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

	private static final class RateLimitState {
		private long lastMessageAt;
		private final ArrayDeque<Long> window;

		private RateLimitState() {
			this.lastMessageAt = 0L;
			this.window = new ArrayDeque<>();
		}
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
