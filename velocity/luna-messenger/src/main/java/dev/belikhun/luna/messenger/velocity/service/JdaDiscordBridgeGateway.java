package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.messenger.velocity.service.discord.DiscordCommandRegistry;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.StringJoiner;

public final class JdaDiscordBridgeGateway extends ListenerAdapter implements DiscordBridgeGateway {
	private static final long SELF_FINGERPRINT_WINDOW_MS = 15000L;
	private static final int MAX_SELF_FINGERPRINTS = 4096;

	@FunctionalInterface
	public interface InboundHandler {
		void handle(DiscordInboundMessage inboundMessage);
	}

	private final LunaLogger logger;
	private final VelocityMessengerConfig.DiscordBotConfig config;
	private final InboundHandler inboundHandler;
	private final JDA jda;
	private final int maxAttempts;
	private final int retryDelayMs;
	private final ExecutorService retryExecutor;
	private final Set<String> channelIds;
	private final Map<String, Long> recentSelfFingerprints;
	private final HttpClient webhookRelayClient;
	private final List<RelayWebhookEndpoint> relayWebhookEndpoints;
	private final VelocityMessengerConfig.DiscordPresenceUpdaterConfig presenceUpdaterConfig;
	private final Supplier<Map<String, String>> presencePlaceholderSupplier;
	private final VelocityMiniPlaceholderResolver miniPlaceholderResolver;
	private final ScheduledExecutorService presenceExecutor;
	private final AtomicInteger rotatingPresenceCursor;
	private final DiscordCommandRegistry commandRegistry;

	public JdaDiscordBridgeGateway(
		LunaLogger logger,
		VelocityMessengerConfig.DiscordBotConfig config,
		VelocityMessengerConfig.DiscordRetryConfig retryConfig,
		InboundHandler inboundHandler,
		List<String> webhookUrls,
		Supplier<Map<String, String>> presencePlaceholderSupplier,
		DiscordCommandRegistry commandRegistry
	) throws Exception {
		this.logger = logger.scope("DiscordBridge");
		this.config = config;
		this.inboundHandler = inboundHandler;
		this.maxAttempts = retryConfig == null ? 3 : Math.max(1, retryConfig.maxAttempts());
		this.retryDelayMs = retryConfig == null ? 500 : Math.max(0, retryConfig.delayMs());
		this.channelIds = new LinkedHashSet<>(config.channelIds());
		this.recentSelfFingerprints = new ConcurrentHashMap<>();
		this.webhookRelayClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.relayWebhookEndpoints = resolveRelayWebhookEndpoints(webhookUrls);
		this.presenceUpdaterConfig = config.presenceUpdater();
		this.presencePlaceholderSupplier = presencePlaceholderSupplier;
		this.miniPlaceholderResolver = new VelocityMiniPlaceholderResolver();
		this.rotatingPresenceCursor = new AtomicInteger();
		this.commandRegistry = commandRegistry;
		this.presenceExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-discord-presence-updater");
			thread.setDaemon(true);
			return thread;
		});
		this.retryExecutor = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-discord-bot-retry");
			thread.setDaemon(true);
			return thread;
		});

		this.jda = JDABuilder.createLight(
			Objects.requireNonNull(config.token(), "discord.bot.token"),
			Objects.requireNonNull(EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT), "gatewayIntents")
		)
			.addEventListeners(this)
			.build()
			.awaitReady();

		this.logger.audit("Discord bot đã kết nối thành công.");
		if (this.commandRegistry != null) {
			this.commandRegistry.registerSlashCommands(this.jda);
		}
		applyStartingPresence();
		startPresenceUpdater();
	}

	private void applyStartingPresence() {
		if (presenceUpdaterConfig == null || !presenceUpdaterConfig.useStartingPresence()) {
			return;
		}

		applyPresence(presenceUpdaterConfig.startingPresence(), "starting");
	}

	private void applyStoppingPresence() {
		if (presenceUpdaterConfig == null || !presenceUpdaterConfig.useStoppingPresence()) {
			return;
		}

		applyPresence(presenceUpdaterConfig.stoppingPresence(), "stopping");
	}

	private void startPresenceUpdater() {
		if (presenceUpdaterConfig == null || presenceUpdaterConfig.presences().isEmpty()) {
			return;
		}

		long periodSeconds = Math.max(30L, presenceUpdaterConfig.updaterRateInSeconds());
		presenceExecutor.scheduleAtFixedRate(
			this::applyRotatingPresence,
			periodSeconds,
			periodSeconds,
			TimeUnit.SECONDS
		);
	}

	private void applyRotatingPresence() {
		if (presenceUpdaterConfig == null || presenceUpdaterConfig.presences().isEmpty()) {
			return;
		}

		List<VelocityMessengerConfig.DiscordPresenceEntry> presences = presenceUpdaterConfig.presences();
		int index = Math.floorMod(rotatingPresenceCursor.getAndIncrement(), presences.size());
		applyPresence(presences.get(index), "rotation");
	}

	private void applyPresence(VelocityMessengerConfig.DiscordPresenceEntry presenceEntry, String source) {
		if (presenceEntry == null) {
			return;
		}

		try {
			Map<String, String> placeholders = presencePlaceholderSupplier == null ? Map.of() : presencePlaceholderSupplier.get();
			String activityName = resolvePresenceTemplate(presenceEntry.activityName(), placeholders);
			String streamUrl = resolvePresenceTemplate(presenceEntry.streamUrl(), placeholders);
			OnlineStatus status = parseOnlineStatus(presenceEntry.status());
			PresenceActivityResolution resolution = resolvePresenceActivity(
				presenceEntry.activityType(),
				activityName,
				streamUrl
			);
			if (resolution.activity() == null) {
				logger.warn("Presence không hợp lệ ở chế độ " + source
					+ " (thiếu activity-name hoặc cấu hình activity-type không phù hợp)."
					+ " Vui lòng kiểm tra discord.bot.presence-updater trong config.yml.");
				return;
			}

			jda.getPresence().setStatus(status);
			jda.getPresence().setActivity(resolution.activity());
			if ("starting".equals(source) || "stopping".equals(source)) {
				logger.audit("Đã cập nhật Discord presence (" + source + "): status=" + status + ", activity=" + resolution.displayText());
			} else {
				logger.debug("Đã cập nhật Discord presence (" + source + "): status=" + status + ", activity=" + resolution.displayText());
			}
		} catch (Exception exception) {
			logger.warn("Không thể cập nhật Discord presence (" + source + "): " + exception.getMessage());
		}
	}

	private String resolvePresenceTemplate(String template, Map<String, String> placeholders) {
		String replaced = applyPlaceholders(template, placeholders);
		String miniResolved = miniPlaceholderResolver.resolve(null, replaced);
		return sanitizePresenceText(miniResolved);
	}

	private String sanitizePresenceText(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}

		return Formatters.stripFormats(raw).trim();
	}

	private PresenceActivityResolution resolvePresenceActivity(
		String activityType,
		String activityName,
		String streamUrl
	) {
		String normalizedType = activityType == null ? "" : activityType.trim().toLowerCase(Locale.ROOT);
		String displayText = activityName == null ? "" : activityName.trim();
		Activity activity = createActivityByType(normalizedType, displayText, streamUrl);
		return new PresenceActivityResolution(activity, displayText);
	}

	private Activity createActivityByType(String normalizedType, String activityText, String streamUrl) {
		String text = activityText == null ? "" : activityText.trim();
		if (text.isEmpty()) {
			return null;
		}

		return switch (normalizedType) {
			case "", "playing" -> Activity.playing(text);
			case "listening" -> Activity.listening(text);
			case "watching" -> Activity.watching(text);
			case "competing", "competing_in", "competing in" -> Activity.competing(text);
			case "streaming" -> {
				String url = streamUrl == null ? "" : streamUrl.trim();
				if (url.isBlank()) {
					yield Activity.playing(text);
				}
				yield Activity.streaming(text, url);
			}
			default -> Activity.playing(text);
		};
	}

	private record PresenceActivityResolution(Activity activity, String displayText) {
	}

	private OnlineStatus parseOnlineStatus(String rawStatus) {
		if (rawStatus == null || rawStatus.isBlank()) {
			return OnlineStatus.ONLINE;
		}

		String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "idle" -> OnlineStatus.IDLE;
			case "do_not_disturb", "dnd" -> OnlineStatus.DO_NOT_DISTURB;
			case "invisible", "offline" -> OnlineStatus.INVISIBLE;
			default -> OnlineStatus.ONLINE;
		};
	}

	private String applyPlaceholders(String text, Map<String, String> placeholders) {
		String output = text == null ? "" : text;
		if (placeholders == null || placeholders.isEmpty()) {
			return output;
		}

		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}

			String value = entry.getValue() == null ? "" : entry.getValue();
			output = output.replace("%" + key + "%", value);
		}

		return output;
	}

	@Override
	public boolean publish(DiscordOutboundMessage outboundMessage) {
		if (outboundMessage == null) {
			return false;
		}

		if (channelIds.isEmpty()) {
			logger.warn("Không có Discord channel-ids hợp lệ để gửi.");
			return false;
		}

		Set<TextChannel> channels = new LinkedHashSet<>();
		for (String channelId : channelIds) {
			if (channelId == null || channelId.isBlank()) {
				continue;
			}

			TextChannel channel = jda.getTextChannelById(channelId);
			if (channel == null) {
				logger.warn("Không tìm thấy Discord channel với id=" + channelId);
				continue;
			}
			channels.add(channel);
		}

		if (channels.isEmpty()) {
			return false;
		}

		if (outboundMessage.embed() != null) {
			EmbedBuilder embedBuilder = new EmbedBuilder();
			DiscordOutboundMessage.Embed embed = outboundMessage.embed();
			if (embed.author() != null && !embed.author().isBlank()) {
				embedBuilder.setAuthor(embed.author(),
					embed.authorUrl() == null || embed.authorUrl().isBlank() ? null : embed.authorUrl(),
					embed.authorIconUrl() == null || embed.authorIconUrl().isBlank() ? null : embed.authorIconUrl());
			}
			if (embed.title() != null && !embed.title().isBlank()) {
				embedBuilder.setTitle(embed.title());
			}
			if (embed.description() != null && !embed.description().isBlank()) {
				embedBuilder.setDescription(embed.description());
			}
			if (embed.color() != null) {
				embedBuilder.setColor(new Color(embed.color()));
			}
			if (embed.thumbnailUrl() != null && !embed.thumbnailUrl().isBlank()) {
				embedBuilder.setThumbnail(embed.thumbnailUrl());
			}
			if (embed.imageUrl() != null && !embed.imageUrl().isBlank()) {
				embedBuilder.setImage(embed.imageUrl());
			}

			for (TextChannel channel : channels) {
				recordSelfFingerprint(outboundMessage.content());
				recordSelfFingerprint(embed.description());
				publishWithRetry(channel, outboundMessage, embedBuilder.build(), 1);
			}
			return true;
		}

		if (outboundMessage.content() == null || outboundMessage.content().isBlank()) {
			return false;
		}

		for (TextChannel channel : channels) {
			recordSelfFingerprint(outboundMessage.content());
			publishWithRetry(channel, outboundMessage, null, 1);
		}

		return true;
	}

	private void publishWithRetry(
		TextChannel channel,
		DiscordOutboundMessage outboundMessage,
		net.dv8tion.jda.api.entities.MessageEmbed embed,
		int attempt
	) {
		String content = outboundMessage.content();

		if (embed != null) {
			if (content != null && !content.isBlank()) {
				channel.sendMessage(content).setEmbeds(embed).queue(
					ignored -> {},
					error -> handleRetry("Gửi Discord embed thất bại", error.getMessage(), channel, outboundMessage, embed, attempt)
				);
				return;
			}

			channel.sendMessageEmbeds(embed).queue(
				ignored -> {},
				error -> handleRetry("Gửi Discord embed thất bại", error.getMessage(), channel, outboundMessage, embed, attempt)
			);
			return;
		}

		if (content == null || content.isBlank()) {
			return;
		}

		channel.sendMessage(content).queue(
			ignored -> {},
			error -> handleRetry("Gửi Discord message thất bại", error.getMessage(), channel, outboundMessage, null, attempt)
		);
	}

	private void handleRetry(
		String reason,
		String detail,
		TextChannel channel,
		DiscordOutboundMessage outboundMessage,
		net.dv8tion.jda.api.entities.MessageEmbed embed,
		int attempt
	) {
		if (attempt >= maxAttempts) {
			logger.warn(reason + ": " + detail);
			return;
		}

		int nextAttempt = attempt + 1;
		logger.warn(reason + " (thử lại " + nextAttempt + "/" + maxAttempts + "): " + detail);
		CompletableFuture.runAsync(() -> {
			try {
				if (retryDelayMs > 0) {
					TimeUnit.MILLISECONDS.sleep(retryDelayMs);
				}
				publishWithRetry(channel, outboundMessage, embed, nextAttempt);
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
			}
		}, retryExecutor);
	}

	@SuppressWarnings("null")
	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (!event.isFromGuild()) {
			return;
		}

		Message message = event.getMessage();
		String sourceChannelId = event.getChannel().getId();
		if (!channelIds.contains(sourceChannelId)) {
			return;
		}

		if (jda.getSelfUser() != null && message.getAuthor().getId().equals(jda.getSelfUser().getId())) {
			return;
		}

		// Webhook messages are already delivered to all configured endpoints by webhook fan-out.
		// Reprocessing them here would create mirror loops and duplicated join/leave notifications.
		if (message.isWebhookMessage()) {
			return;
		}

		if (config.ignoreBotMessages() && message.getAuthor().isBot()) {
			return;
		}

		if (commandRegistry != null && !message.getAuthor().isBot() && commandRegistry.handleMessageCommand(event)) {
			return;
		}

		String content = message.getContentDisplay();
		if ((content == null || content.isBlank()) && !message.getEmbeds().isEmpty()) {
			content = message.getEmbeds().get(0).getDescription();
		}

		if (message.getAuthor().isBot() && isSelfEcho(content)) {
			return;
		}

		relayInboundToOtherChannels(message, sourceChannelId);

		if (content == null || content.isBlank()) {
			return;
		}

		String authorUsername = message.getAuthor().getName();
		String authorNickname = message.getMember() == null ? authorUsername : message.getMember().getEffectiveName();
		String authorName = (authorNickname == null || authorNickname.isBlank()) ? authorUsername : authorNickname;

		inboundHandler.handle(new DiscordInboundMessage(
			authorName,
			authorUsername,
			authorNickname,
			content,
			config.sourceId(),
			message.getId(),
			message.getAuthor().getId()
		));
	}

	@Override
	@SuppressWarnings("null")
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		if (!event.isFromGuild()) {
			return;
		}

		String sourceChannelId = event.getChannel().getId();
		if (!channelIds.contains(sourceChannelId)) {
			event.reply("❌ Lệnh này chỉ hoạt động trong kênh chat Minecraft.").setEphemeral(true).queue();
			return;
		}

		if (commandRegistry == null) {
			return;
		}

		commandRegistry.handleSlashCommand(event);
	}

	private void relayInboundToOtherChannels(Message sourceMessage, String sourceChannelId) {
		if (channelIds.size() <= 1) {
			return;
		}

		if (relayToOtherChannelsViaWebhook(sourceMessage, sourceChannelId)) {
			return;
		}

		String content = sourceMessage.getContentDisplay();
		if ((content == null || content.isBlank()) && !sourceMessage.getEmbeds().isEmpty()) {
			content = sourceMessage.getEmbeds().get(0).getDescription();
		}
		if (content == null || content.isBlank()) {
			return;
		}

		String relayedContent = "[" + sourceMessage.getAuthor().getName() + "] " + content;
		recordSelfFingerprint(relayedContent);
		for (String channelId : channelIds) {
			if (channelId.equals(sourceChannelId)) {
				continue;
			}

			TextChannel targetChannel = jda.getTextChannelById(channelId);
			if (targetChannel == null) {
				continue;
			}

			targetChannel.sendMessage(relayedContent).queue(
				ignored -> {},
				error -> logger.warn("Không thể sync Discord message sang channel=" + channelId + ": " + error.getMessage())
			);
		}
	}

	private boolean relayToOtherChannelsViaWebhook(Message sourceMessage, String sourceChannelId) {
		if (relayWebhookEndpoints.isEmpty()) {
			return false;
		}

		DiscordOutboundMessage mirrored = toMirroredWebhookMessage(sourceMessage);
		if (mirrored == null) {
			return false;
		}

		recordSelfFingerprint(mirrored.content());
		if (mirrored.embed() != null && mirrored.embed().description() != null) {
			recordSelfFingerprint(mirrored.embed().description());
		}

		boolean sent = false;
		for (RelayWebhookEndpoint endpoint : relayWebhookEndpoints) {
			if (endpoint.channelId().equals(sourceChannelId)) {
				continue;
			}

			if (sendWebhookMessage(endpoint.webhookUri(), mirrored)) {
				sent = true;
			}
		}

		return sent;
	}

	private DiscordOutboundMessage toMirroredWebhookMessage(Message sourceMessage) {
		String content = buildMirroredContent(sourceMessage);
		DiscordOutboundMessage.Embed embed = null;

		if (!sourceMessage.getEmbeds().isEmpty()) {
			MessageEmbed sourceEmbed = sourceMessage.getEmbeds().get(0);
			MessageEmbed.AuthorInfo author = sourceEmbed.getAuthor();
			MessageEmbed.Thumbnail thumbnail = sourceEmbed.getThumbnail();
			MessageEmbed.ImageInfo image = sourceEmbed.getImage();
			String authorName = author == null ? null : author.getName();
			String authorUrl = author == null ? null : author.getUrl();
			String authorIconUrl = author == null ? null : author.getIconUrl();
			String thumbnailUrl = thumbnail == null ? null : thumbnail.getUrl();
			String imageUrl = image == null ? null : image.getUrl();
			embed = new DiscordOutboundMessage.Embed(
				authorName,
				authorUrl,
				authorIconUrl,
				sourceEmbed.getTitle(),
				sourceEmbed.getDescription(),
				sourceEmbed.getColorRaw(),
				thumbnailUrl,
				imageUrl
			);
		}

		if ((content == null || content.isBlank()) && embed == null) {
			return null;
		}

		String relayUsername = sourceMessage.getAuthor().getName();
		var member = sourceMessage.getMember();
		if (member != null) {
			relayUsername = member.getEffectiveName();
		}

		return new DiscordOutboundMessage(
			DiscordOutboundMessage.DispatchType.PLAYER_CHAT,
			relayUsername,
			sourceMessage.getAuthor().getEffectiveAvatarUrl(),
			content,
			embed
		);
	}

	private String buildMirroredContent(Message sourceMessage) {
		String content = sourceMessage.getContentRaw();
		if ((content == null || content.isBlank()) && !sourceMessage.getEmbeds().isEmpty()) {
			content = sourceMessage.getEmbeds().get(0).getDescription();
		}

		String attachmentText = collectAttachmentUrls(sourceMessage);
		if (attachmentText.isBlank()) {
			return content == null ? "" : content.trim();
		}

		String base = content == null ? "" : content.trim();
		if (base.isBlank()) {
			return attachmentText;
		}

		return base + "\n" + attachmentText;
	}

	private String collectAttachmentUrls(Message sourceMessage) {
		if (sourceMessage.getAttachments().isEmpty()) {
			return "";
		}

		StringJoiner joiner = new StringJoiner("\n");
		for (Message.Attachment attachment : sourceMessage.getAttachments()) {
			String url = attachment == null ? "" : attachment.getUrl();
			if (url != null && !url.isBlank()) {
				joiner.add(url.trim());
			}
		}

		return joiner.toString().trim();
	}

	private List<RelayWebhookEndpoint> resolveRelayWebhookEndpoints(List<String> webhookUrls) {
		List<RelayWebhookEndpoint> output = new ArrayList<>();
		if (webhookUrls == null || webhookUrls.isEmpty()) {
			return output;
		}

		for (String rawUrl : webhookUrls) {
			if (rawUrl == null || rawUrl.isBlank()) {
				continue;
			}

			URI webhookUri;
			try {
				webhookUri = URI.create(rawUrl.trim());
			} catch (IllegalArgumentException exception) {
				logger.warn("Webhook relay: URL không hợp lệ: " + rawUrl);
				continue;
			}

			try {
				HttpRequest request = HttpRequest.newBuilder(webhookUri)
					.timeout(Duration.ofSeconds(5))
					.GET()
					.build();
				HttpResponse<String> response = webhookRelayClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					logger.warn("Webhook relay: không thể resolve channel-id từ webhook (HTTP " + response.statusCode() + ").");
					continue;
				}

				String responseBody = response.body();
				DataObject data = DataObject.fromJson(responseBody == null ? "{}" : responseBody);
				String channelId = data.getString("channel_id", "").trim();
				if (channelId.isEmpty()) {
					logger.warn("Webhook relay: thiếu channel_id từ webhook response.");
					continue;
				}

				output.add(new RelayWebhookEndpoint(channelId, webhookUri));
			} catch (Exception exception) {
				logger.warn("Webhook relay: không thể resolve webhook metadata: " + exception.getMessage());
			}
		}

		return output;
	}

	private boolean sendWebhookMessage(URI webhookUri, DiscordOutboundMessage message) {
		try {
			HttpRequest request = HttpRequest.newBuilder(webhookUri)
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(toWebhookJson(message), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = webhookRelayClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			int code = response.statusCode();
			if (code >= 200 && code < 300) {
				return true;
			}

			logger.warn("Webhook relay: gửi mirror thất bại (HTTP " + code + "): " + response.body());
			return false;
		} catch (Exception exception) {
			logger.warn("Webhook relay: gửi mirror thất bại: " + exception.getMessage());
			return false;
		}
	}

	private String toWebhookJson(DiscordOutboundMessage message) {
		DataObject root = DataObject.empty();
		putIfText(root, "username", message.username());
		putIfText(root, "avatar_url", message.avatarUrl());
		putIfText(root, "content", message.content());
		if (message.embed() != null) {
			root.put("embeds", DataArray.empty().add(embedJson(message.embed())));
		}
		return root.toString();
	}

	private DataObject embedJson(DiscordOutboundMessage.Embed embed) {
		DataObject embedObject = DataObject.empty();
		if (embed.author() != null && !embed.author().isBlank()) {
			DataObject authorObject = DataObject.empty();
			authorObject.put("name", embed.author());
			if (embed.authorUrl() != null && !embed.authorUrl().isBlank()) {
				authorObject.put("url", embed.authorUrl());
			}
			if (embed.authorIconUrl() != null && !embed.authorIconUrl().isBlank()) {
				authorObject.put("icon_url", embed.authorIconUrl());
			}
			embedObject.put("author", authorObject);
		}
		putIfText(embedObject, "title", embed.title());
		putIfText(embedObject, "description", embed.description());
		if (embed.color() != null) {
			embedObject.put("color", embed.color());
		}
		if (embed.thumbnailUrl() != null && !embed.thumbnailUrl().isBlank()) {
			embedObject.put("thumbnail", DataObject.empty().put("url", embed.thumbnailUrl()));
		}
		if (embed.imageUrl() != null && !embed.imageUrl().isBlank()) {
			embedObject.put("image", DataObject.empty().put("url", embed.imageUrl()));
		}
		return embedObject;
	}

	@SuppressWarnings("null")
	private void putIfText(DataObject object, String key, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		object.put(key, value);
	}

	private record RelayWebhookEndpoint(String channelId, URI webhookUri) {
	}

	private void recordSelfFingerprint(String content) {
		if (content == null || content.isBlank()) {
			return;
		}

		long now = System.currentTimeMillis();
		recentSelfFingerprints.entrySet().removeIf(entry -> now - entry.getValue() > SELF_FINGERPRINT_WINDOW_MS);
		recentSelfFingerprints.put(content.trim().toLowerCase(), now);

		while (recentSelfFingerprints.size() > MAX_SELF_FINGERPRINTS) {
			String oldestKey = null;
			long oldestAt = Long.MAX_VALUE;
			for (Map.Entry<String, Long> entry : recentSelfFingerprints.entrySet()) {
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

			recentSelfFingerprints.remove(oldestKey);
		}
	}

	private boolean isSelfEcho(String content) {
		if (content == null || content.isBlank()) {
			return false;
		}

		String key = content.trim().toLowerCase();
		Long at = recentSelfFingerprints.get(key);
		if (at == null) {
			return false;
		}

		return System.currentTimeMillis() - at <= SELF_FINGERPRINT_WINDOW_MS;
	}

	@Override
	public void close() {
		presenceExecutor.shutdownNow();
		applyStoppingPresence();
		try {
			jda.shutdownNow();
		} catch (Exception ignored) {
		}
		retryExecutor.shutdownNow();
		logger.audit("Đã đóng Discord bot gateway.");
	}
}
