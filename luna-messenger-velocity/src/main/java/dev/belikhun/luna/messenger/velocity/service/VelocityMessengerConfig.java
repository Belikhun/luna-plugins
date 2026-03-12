package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class VelocityMessengerConfig {
	private final FormatProfile defaults;
	private final Map<String, FormatProfile> perServer;
	private final String userDisplayFormat;
	private final Map<String, String> serverDisplays;
	private final String defaultServerColor;
	private final Map<String, String> serverColors;
	private final MentionConfig mentions;
	private final SilentBroadcastConfig silentBroadcast;
	private final DiscordConfig discord;
	private final RateLimitConfig rateLimit;

	private VelocityMessengerConfig(
		FormatProfile defaults,
		Map<String, FormatProfile> perServer,
		String userDisplayFormat,
		Map<String, String> serverDisplays,
		String defaultServerColor,
		Map<String, String> serverColors,
		MentionConfig mentions,
		SilentBroadcastConfig silentBroadcast,
		DiscordConfig discord,
		RateLimitConfig rateLimit
	) {
		this.defaults = defaults;
		this.perServer = perServer;
		this.userDisplayFormat = userDisplayFormat;
		this.serverDisplays = serverDisplays;
		this.defaultServerColor = defaultServerColor;
		this.serverColors = serverColors;
		this.mentions = mentions;
		this.silentBroadcast = silentBroadcast;
		this.discord = discord;
		this.rateLimit = rateLimit;
	}

	public static VelocityMessengerConfig load(Path path) {
		Map<String, Object> root = LunaYamlConfig.loadMap(path);
		Map<String, Object> formats = map(root.get("formats"));
		FormatProfile defaults = parseProfile(map(formats.get("defaults")), defaultProfile());

		Map<String, FormatProfile> perServer = new HashMap<>();
		Map<String, Object> servers = map(formats.get("servers"));
		for (Map.Entry<String, Object> entry : servers.entrySet()) {
			String serverName = entry.getKey().toLowerCase(Locale.ROOT);
			FormatProfile profile = parseProfile(map(entry.getValue()), defaults);
			perServer.put(serverName, profile);
		}

		Map<String, String> serverDisplays = new HashMap<>();
		Map<String, Object> displayMap = map(root.get("server-displays"));
		for (Map.Entry<String, Object> entry : displayMap.entrySet()) {
			serverDisplays.put(entry.getKey().toLowerCase(Locale.ROOT), String.valueOf(entry.getValue()));
		}

		Map<String, Object> colorMap = map(root.get("server-colors"));
		String defaultServerColor = str(colorMap.get("default"), "#F1FF68");
		Map<String, String> serverColors = new HashMap<>();
		for (Map.Entry<String, Object> entry : colorMap.entrySet()) {
			if ("default".equalsIgnoreCase(entry.getKey())) {
				continue;
			}
			serverColors.put(entry.getKey().toLowerCase(Locale.ROOT), String.valueOf(entry.getValue()));
		}

		MentionConfig mentions = parseMentionConfig(map(root.get("mentions")));
		SilentBroadcastConfig silentBroadcast = parseSilentBroadcastConfig(map(root.get("silent-broadcast")));
		DiscordConfig discord = parseDiscordConfig(map(root.get("discord")));
		RateLimitConfig rateLimit = parseRateLimitConfig(map(root.get("rate-limit")));
		return new VelocityMessengerConfig(
			defaults,
			Map.copyOf(perServer),
			str(formats.get("user-display-format"), "%player_prefix% %displayname%"),
			Map.copyOf(serverDisplays),
			defaultServerColor,
			Map.copyOf(serverColors),
			mentions,
			silentBroadcast,
			discord,
			rateLimit
		);
	}

	public FormatProfile profileForServer(String serverName) {
		if (serverName == null || serverName.isBlank()) {
			return defaults;
		}
		return perServer.getOrDefault(serverName.toLowerCase(Locale.ROOT), defaults);
	}

	public DiscordConfig discord() {
		return discord;
	}

	public MentionConfig mentions() {
		return mentions;
	}

	public SilentBroadcastConfig silentBroadcast() {
		return silentBroadcast;
	}

	public String userDisplayFormat() {
		return userDisplayFormat;
	}

	public RateLimitConfig rateLimit() {
		return rateLimit;
	}

	public String serverDisplay(String serverName) {
		if (serverName == null || serverName.isBlank()) {
			return "";
		}
		return serverDisplays.getOrDefault(serverName.toLowerCase(Locale.ROOT), serverName);
	}

	public String serverColor(String serverName) {
		if (serverName == null || serverName.isBlank()) {
			return defaultServerColor;
		}
		return serverColors.getOrDefault(serverName.toLowerCase(Locale.ROOT), defaultServerColor);
	}

	private static MentionConfig parseMentionConfig(Map<String, Object> mentions) {
		Map<String, Object> toast = map(mentions.get("toast"));
		return new MentionConfig(
			bool(mentions.get("enabled"), true),
			bool(mentions.get("exact-username-only"), true),
			str(mentions.get("sound"), "ENTITY_PLAYER_LEVELUP"),
			str(mentions.get("highlight-format"), "&e@%name%&r"),
			str(mentions.get("alert-format"), "<yellow>⚠ <white>%sender_name%</white> đã nhắc đến bạn.</yellow>"),
			bool(toast.get("enabled"), true),
			str(toast.get("title"), "<yellow>Bạn được nhắc đến</yellow>"),
			str(toast.get("subtitle"), "<white>%sender_name%</white> vừa nhắc bạn trong chat"),
			integer(toast.get("fade-in-ms"), 200),
			integer(toast.get("stay-ms"), 2000),
			integer(toast.get("fade-out-ms"), 300)
		);
	}

	private static DiscordConfig parseDiscordConfig(Map<String, Object> discord) {
		Map<String, Object> webhook = map(discord.get("webhook"));
		Map<String, Object> bot = map(discord.get("bot"));
		Map<String, Object> presenceUpdater = map(bot.get("presence-updater"));
		Map<String, Object> retry = map(discord.get("retry"));
		Map<String, Object> outbound = map(discord.get("outbound"));
		Map<String, Object> network = map(outbound.get("network"));
		Map<String, Object> joinLeave = map(outbound.get("join-leave"));
		return new DiscordConfig(
			bool(discord.get("enabled"), false),
			str(discord.get("network-channel-name"), "network"),
			new DiscordRetryConfig(
				Math.max(1, integer(retry.get("max-attempts"), 3)),
				Math.max(0, integer(retry.get("delay-ms"), 500))
			),
			new DiscordBotConfig(
				bool(bot.get("enabled"), false),
				str(bot.get("token"), ""),
				List.copyOf(parseChannelIds(bot)),
				bool(bot.get("ignore-bot-messages"), true),
				str(bot.get("source-id"), "discord-bot-jda"),
				parsePresenceUpdaterConfig(presenceUpdater)
			),
			List.copyOf(parseWebhookUrls(webhook)),
			str(webhook.get("username-format"), "%luckperms_prefix% %sender_name%"),
			str(webhook.get("avatar-url-format"), "https://visage.surgeplay.com/bust/128/<skinsrestorer_texture_id_or_steve>.png"),
			parseMessageRoute(map(network.get("message")), defaultNetworkRoute()),
			parseMessageRoute(map(joinLeave.get("join")), defaultJoinRoute()),
			parseMessageRoute(map(joinLeave.get("leave")), defaultLeaveRoute()),
			parseMessageRoute(map(joinLeave.get("switch")), defaultSwitchRoute())
		);
	}

	private static DiscordPresenceUpdaterConfig parsePresenceUpdaterConfig(Map<String, Object> section) {
		Map<String, Object> startingPresence = map(section.get("starting-presence"));
		Map<String, Object> stoppingPresence = map(section.get("stopping-presence"));
		List<DiscordPresenceEntry> presences = new ArrayList<>();

		Object rawPresences = section.get("presences");
		if (rawPresences instanceof Iterable<?> iterable) {
			for (Object item : iterable) {
				Map<String, Object> presence = map(item);
				presences.add(new DiscordPresenceEntry(
					str(presence.get("status"), "online"),
					str(presence.get("activity-type"), "playing"),
					str(presence.get("activity-name"), "Minecraft"),
					str(presence.get("stream-url"), ""),
					str(presence.get("activity"), "Minecraft")
				));
			}
		}

		if (presences.isEmpty()) {
			presences.add(new DiscordPresenceEntry("online", "playing", "Minecraft", "", "Minecraft"));
		}

		return new DiscordPresenceUpdaterConfig(
			bool(section.get("use-starting-presence"), true),
			new DiscordPresenceEntry(
				str(startingPresence.get("status"), "do_not_disturb"),
				str(startingPresence.get("activity-type"), "playing"),
				str(startingPresence.get("activity-name"), "Đang khởi động..."),
				str(startingPresence.get("stream-url"), ""),
				str(startingPresence.get("activity"), "Đang khởi động...")
			),
			bool(section.get("use-stopping-presence"), true),
			new DiscordPresenceEntry(
				str(stoppingPresence.get("status"), "idle"),
				str(stoppingPresence.get("activity-type"), "playing"),
				str(stoppingPresence.get("activity-name"), "Đang dừng..."),
				str(stoppingPresence.get("stream-url"), ""),
				str(stoppingPresence.get("activity"), "Đang dừng...")
			),
			Math.max(30, integer(section.get("updater-rate-in-seconds"), 90)),
			List.copyOf(presences)
		);
	}

	private static SilentBroadcastConfig parseSilentBroadcastConfig(Map<String, Object> silentBroadcast) {
		return new SilentBroadcastConfig(
			bool(silentBroadcast.get("enabled"), true),
			str(silentBroadcast.get("permission"), "lunamessenger.silentbroadcast"),
			strPreserveWhitespace(silentBroadcast.get("prefix"), "<dark_gray>[xuỵt]</dark_gray> ")
		);
	}

	private static RateLimitConfig parseRateLimitConfig(Map<String, Object> rateLimit) {
		return new RateLimitConfig(
			bool(rateLimit.get("enabled"), true),
			integer(rateLimit.get("cooldown-ms"), 500),
			integer(rateLimit.get("window-ms"), 5000),
			integer(rateLimit.get("max-messages"), 6),
			str(rateLimit.get("bypass-permission"), "lunamessenger.bypass.ratelimit")
		);
	}

	private static MessageRouteConfig parseMessageRoute(Map<String, Object> map, MessageRouteConfig fallback) {
		Map<String, Object> embed = map(map.get("embed"));
		return new MessageRouteConfig(
			bool(map.get("enabled"), fallback.enabled()),
			PayloadType.byName(str(map.get("mode"), fallback.mode().name())),
			str(map.get("content"), fallback.content()),
			str(embed.get("author"), fallback.embedAuthor()),
			str(embed.get("author-url"), fallback.embedAuthorUrl()),
			str(embed.get("author-icon-url"), fallback.embedAuthorIconUrl()),
			str(embed.get("title"), fallback.embedTitle()),
			str(embed.get("description"), fallback.embedDescription()),
			parseColor(embed.get("color"), fallback.embedColor()),
			str(embed.get("thumbnail-url"), fallback.embedThumbnailUrl()),
			str(embed.get("image-url"), fallback.embedImageUrl())
		);
	}

	private static Integer parseColor(Object value, Integer fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}

		if (value instanceof String text) {
			String normalized = text.trim();
			if (normalized.isEmpty()) {
				return fallback;
			}

			try {
				if (normalized.startsWith("#")) {
					return Integer.parseInt(normalized.substring(1), 16);
				}
				if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
					return Integer.parseInt(normalized.substring(2), 16);
				}
				return Integer.parseInt(normalized);
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}

		return fallback;
	}

	private static List<String> parseChannelIds(Map<String, Object> bot) {
		Set<String> output = new LinkedHashSet<>();
		addChannelIds(output, bot.get("channel-ids"));
		return new ArrayList<>(output);
	}

	private static List<String> parseWebhookUrls(Map<String, Object> webhook) {
		Set<String> output = new LinkedHashSet<>();
		addChannelIds(output, webhook.get("urls"));
		return new ArrayList<>(output);
	}

	private static void addChannelIds(Set<String> output, Object rawValue) {
		if (rawValue == null) {
			return;
		}

		if (rawValue instanceof Iterable<?> iterable) {
			for (Object item : iterable) {
				String value = String.valueOf(item).trim();
				if (!value.isEmpty()) {
					output.add(value);
				}
			}
			return;
		}

		String text = String.valueOf(rawValue).trim();
		if (text.isEmpty()) {
			return;
		}

		for (String token : text.split(",")) {
			String value = token.trim();
			if (!value.isEmpty()) {
				output.add(value);
			}
		}
	}

	private static FormatProfile parseProfile(Map<String, Object> map, FormatProfile fallback) {
		Map<String, Object> channel = map(map.get("channel"));
		Map<String, Object> direct = map(map.get("direct"));
		Map<String, Object> reply = map(map.get("reply"));
		Map<String, Object> broadcast = map(map.get("broadcast"));
		Map<String, Object> presence = map(map.get("presence"));
		Map<String, Object> firstJoin = map(presence.get("first-join"));
		Map<String, Object> join = map(presence.get("join"));
		Map<String, Object> leave = map(presence.get("leave"));
		Map<String, Object> serverSwitch = map(presence.get("server-switch"));
		Map<String, Object> discord = map(map.get("discord"));
		return new FormatProfile(
			str(channel.get("network"), fallback.networkFormat()),
			str(channel.get("server"), fallback.serverFormat()),
			str(direct.get("to-sender"), fallback.directToSenderFormat()),
			str(direct.get("to-receiver"), fallback.directToReceiverFormat()),
			str(reply.get("to-sender"), fallback.replyToSenderFormat()),
			str(reply.get("to-receiver"), fallback.replyToReceiverFormat()),
			str(broadcast.get("format"), fallback.broadcastFormat()),
			str(firstJoin.get("format"), fallback.firstJoinNetworkFormat()),
			str(join.get("format"), fallback.joinNetworkFormat()),
			str(leave.get("format"), fallback.leaveNetworkFormat()),
			bool(serverSwitch.get("enabled"), fallback.serverSwitchEnabled()),
			str(serverSwitch.get("format"), fallback.serverSwitchFormat()),
			str(discord.get("inbound-network"), fallback.discordInboundNetworkFormat()),
			str(discord.get("outbound-network"), fallback.discordOutboundNetworkFormat())
		);
	}

	private static FormatProfile defaultProfile() {
		return new FormatProfile(
			"<color:%server_color%>⏺</color> <gray>[<gold>N</gold>]</gray> %player_display% <gray><bold>>></bold></gray> <white>%message%</white>",
			"<color:%server_color%>⏺</color> <gray>[<aqua>S</aqua>]</gray> %player_display% <gray><bold>>></bold></gray> <white>%message%</white>",
			"<gray>[DM] Bạn nhắn cho</gray> %receiver_display%<gray>:</gray>",
			"<gray>[DM]</gray> %sender_display% <gray>nhắn cho bạn:</gray>",
			"<gray>[DM] Bạn trả lời</gray> %receiver_display%<gray>:</gray>",
			"<gray>[DM]</gray> %sender_display% <gray>trả lời bạn:</gray>",
			"<gray>[<gold>THÔNG BÁO</gold>]</gray> <white>%message%</white>",
			"<gray>[<green>++<gray>]<reset> %player_display%",
			"<gray>[<green>+<gray>]<reset> %player_display%",
			"<gray>[<red>-<gray>]<reset> %player_display%",
			true,
			"<gray>[<yellow>%from_display%</yellow> <white><bold>-></bold> <blue>%to_display%</blue><gray>]</gray> %player_display%",
			"<gray>[<color:#5865F2>#%channel_name%</color>]</gray> <light_purple>%discord_author%</light_purple> <gray><bold>>></bold></gray> <white>%message%</white>",
			"[%channel_name%] %sender_name%: %message%"
		);
	}

	private static MessageRouteConfig defaultNetworkRoute() {
		return new MessageRouteConfig(
			true,
			PayloadType.TEXT,
			"[%channel_name%] %sender_name%: %message%",
			"",
			"",
			"",
			"",
			"",
			null,
			"",
			""
		);
	}

	private static MessageRouteConfig defaultJoinRoute() {
		return new MessageRouteConfig(
			true,
			PayloadType.TEXT,
			"[JOIN] %sender_name% đã vào mạng (%server_name%)",
			"",
			"",
			"",
			"",
			"",
			null,
			"",
			""
		);
	}

	private static MessageRouteConfig defaultLeaveRoute() {
		return new MessageRouteConfig(
			true,
			PayloadType.TEXT,
			"[LEAVE] %sender_name% đã rời mạng",
			"",
			"",
			"",
			"",
			"",
			null,
			"",
			""
		);
	}

	private static MessageRouteConfig defaultSwitchRoute() {
		return new MessageRouteConfig(
			true,
			PayloadType.TEXT,
			"[SWAP] %sender_name% chuyển %from_server% -> %to_server%",
			"",
			"",
			"",
			"",
			"",
			null,
			"",
			""
		);
	}

	private static Map<String, Object> map(Object value) {
		return ConfigValues.map(value);
	}

	private static String str(Object value, String fallback) {
		return ConfigValues.string(value, fallback);
	}

	private static String strPreserveWhitespace(Object value, String fallback) {
		return ConfigValues.stringPreserveWhitespace(value, fallback);
	}

	private static boolean bool(Object value, boolean fallback) {
		return ConfigValues.booleanValue(value, fallback);
	}

	private static Integer integer(Object value, Integer fallback) {
		return ConfigValues.integerValue(value, fallback);
	}

	public record FormatProfile(
		String networkFormat,
		String serverFormat,
		String directToSenderFormat,
		String directToReceiverFormat,
		String replyToSenderFormat,
		String replyToReceiverFormat,
		String broadcastFormat,
		String firstJoinNetworkFormat,
		String joinNetworkFormat,
		String leaveNetworkFormat,
		boolean serverSwitchEnabled,
		String serverSwitchFormat,
		String discordInboundNetworkFormat,
		String discordOutboundNetworkFormat
	) {
	}

	public record DiscordConfig(
		boolean enabled,
		String networkChannelName,
		DiscordRetryConfig retry,
		DiscordBotConfig bot,
		List<String> webhookUrls,
		String webhookUsernameFormat,
		String avatarUrlFormat,
		MessageRouteConfig networkMessage,
		MessageRouteConfig joinMessage,
		MessageRouteConfig leaveMessage,
		MessageRouteConfig switchMessage
	) {
		public DiscordConfig {
			webhookUrls = webhookUrls == null ? List.of() : List.copyOf(webhookUrls);
		}
	}

	public record DiscordRetryConfig(
		int maxAttempts,
		int delayMs
	) {
	}

	public record DiscordBotConfig(
		boolean enabled,
		String token,
		List<String> channelIds,
		boolean ignoreBotMessages,
		String sourceId,
		DiscordPresenceUpdaterConfig presenceUpdater
	) {
		public DiscordBotConfig {
			channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
			presenceUpdater = presenceUpdater == null ? new DiscordPresenceUpdaterConfig(
				true,
				new DiscordPresenceEntry("do_not_disturb", "playing", "Đang khởi động...", "", "Đang khởi động..."),
				true,
				new DiscordPresenceEntry("idle", "playing", "Đang dừng...", "", "Đang dừng..."),
				90,
				List.of(new DiscordPresenceEntry("online", "playing", "Minecraft", "", "Minecraft"))
			) : presenceUpdater;
		}
	}

	public record DiscordPresenceUpdaterConfig(
		boolean useStartingPresence,
		DiscordPresenceEntry startingPresence,
		boolean useStoppingPresence,
		DiscordPresenceEntry stoppingPresence,
		int updaterRateInSeconds,
		List<DiscordPresenceEntry> presences
	) {
		public DiscordPresenceUpdaterConfig {
			startingPresence = startingPresence == null ? new DiscordPresenceEntry("do_not_disturb", "playing", "Đang khởi động...", "", "Đang khởi động...") : startingPresence;
			stoppingPresence = stoppingPresence == null ? new DiscordPresenceEntry("idle", "playing", "Đang dừng...", "", "Đang dừng...") : stoppingPresence;
			updaterRateInSeconds = Math.max(30, updaterRateInSeconds);
			presences = presences == null || presences.isEmpty()
				? List.of(new DiscordPresenceEntry("online", "playing", "Minecraft", "", "Minecraft"))
				: List.copyOf(presences);
		}
	}

	public record DiscordPresenceEntry(
		String status,
		String activityType,
		String activityName,
		String streamUrl,
		String activity
	) {
		public DiscordPresenceEntry(String status, String activityType, String activityName, String streamUrl) {
			this(status, activityType, activityName, streamUrl, "");
		}

		public DiscordPresenceEntry(String status, String activityName) {
			this(status, "playing", activityName, "", "");
		}

		public DiscordPresenceEntry {
			status = (status == null || status.isBlank()) ? "online" : status;
			activityType = activityType == null ? "" : activityType;
			activityName = activityName == null ? "" : activityName;
			streamUrl = streamUrl == null ? "" : streamUrl;
			activity = activity == null ? "" : activity;
		}
	}

	public record MentionConfig(
		boolean enabled,
		boolean exactUsernameOnly,
		String sound,
		String highlightFormat,
		String alertFormat,
		boolean toastEnabled,
		String toastTitle,
		String toastSubtitle,
		Integer toastFadeInMs,
		Integer toastStayMs,
		Integer toastFadeOutMs
	) {
	}

	public record SilentBroadcastConfig(
		boolean enabled,
		String permission,
		String prefix
	) {
	}

	public record MessageRouteConfig(
		boolean enabled,
		PayloadType mode,
		String content,
		String embedAuthor,
		String embedAuthorUrl,
		String embedAuthorIconUrl,
		String embedTitle,
		String embedDescription,
		Integer embedColor,
		String embedThumbnailUrl,
		String embedImageUrl
	) {
	}

	public record RateLimitConfig(
		boolean enabled,
		Integer cooldownMs,
		Integer windowMs,
		Integer maxMessages,
		String bypassPermission
	) {
	}

	public enum PayloadType {
		TEXT,
		EMBED;

		public static PayloadType byName(String value) {
			for (PayloadType type : values()) {
				if (type.name().equalsIgnoreCase(value)) {
					return type;
				}
			}
			return TEXT;
		}
	}
}
