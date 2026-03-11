package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.StringJoiner;

public final class JdaDiscordBridgeGateway extends ListenerAdapter implements DiscordBridgeGateway {
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

	public JdaDiscordBridgeGateway(
		LunaLogger logger,
		VelocityMessengerConfig.DiscordBotConfig config,
		VelocityMessengerConfig.DiscordRetryConfig retryConfig,
		InboundHandler inboundHandler,
		List<String> webhookUrls
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
			String authorName = sourceEmbed.getAuthor() == null ? null : sourceEmbed.getAuthor().getName();
			String authorUrl = sourceEmbed.getAuthor() == null ? null : sourceEmbed.getAuthor().getUrl();
			String authorIconUrl = sourceEmbed.getAuthor() == null ? null : sourceEmbed.getAuthor().getIconUrl();
			String thumbnailUrl = sourceEmbed.getThumbnail() == null ? null : sourceEmbed.getThumbnail().getUrl();
			String imageUrl = sourceEmbed.getImage() == null ? null : sourceEmbed.getImage().getUrl();
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

		return new DiscordOutboundMessage(
			DiscordOutboundMessage.DispatchType.PLAYER_CHAT,
			sourceMessage.getMember() == null
				? sourceMessage.getAuthor().getName()
				: sourceMessage.getMember().getEffectiveName(),
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

				DataObject data = DataObject.fromJson(response.body());
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
		recentSelfFingerprints.entrySet().removeIf(entry -> now - entry.getValue() > 15000L);
		recentSelfFingerprints.put(content.trim().toLowerCase(), now);
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

		return System.currentTimeMillis() - at <= 15000L;
	}

	@Override
	public void close() {
		try {
			jda.shutdownNow();
		} catch (Exception ignored) {
		}
		retryExecutor.shutdownNow();
		logger.audit("Đã đóng Discord bot gateway.");
	}
}
