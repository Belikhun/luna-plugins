package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class WebhookDiscordBridgeGateway implements DiscordBridgeGateway {
	private final LunaLogger logger;
	private final List<URI> webhookUris;
	private final HttpClient httpClient;
	private final int maxAttempts;
	private final int retryDelayMs;

	public WebhookDiscordBridgeGateway(
		LunaLogger logger,
		List<String> webhookUrls,
		VelocityMessengerConfig.DiscordRetryConfig retryConfig
	) {
		this.logger = logger.scope("DiscordBridge");
		this.webhookUris = parseWebhookUris(webhookUrls);
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.maxAttempts = retryConfig == null ? 3 : Math.max(1, retryConfig.maxAttempts());
		this.retryDelayMs = retryConfig == null ? 500 : Math.max(0, retryConfig.delayMs());
	}

	@Override
	public boolean publish(DiscordOutboundMessage message) {
		if ((message.content() == null || message.content().isBlank()) && message.embed() == null) {
			return false;
		}

		if (webhookUris.isEmpty()) {
			logger.warn("Không có Discord webhook URL hợp lệ để gửi.");
			return false;
		}

		String payload = toWebhookJson(message);
		boolean anySuccess = false;
		for (URI webhookUri : webhookUris) {
			if (publishWithRetry(webhookUri, payload)) {
				anySuccess = true;
			}
		}

		return anySuccess;
	}

	private boolean publishWithRetry(URI webhookUri, String payload) {
		String endpoint = webhookUri.toString();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder(webhookUri)
					.timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
					.build();

				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				int code = response.statusCode();
				if (code >= 200 && code < 300) {
					return true;
				}

				boolean retryable = code == 429 || code >= 500;
				if (!retryable || attempt >= maxAttempts) {
					logger.warn("Discord webhook [" + endpoint + "] trả về mã lỗi " + code + ": " + response.body());
					return false;
				}

				logger.warn("Discord webhook [" + endpoint + "] trả về mã lỗi " + code
					+ " (thử lại " + (attempt + 1) + "/" + maxAttempts + "): " + response.body());
				if (retryDelayMs > 0) {
					TimeUnit.MILLISECONDS.sleep(retryDelayMs);
				}
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
				return false;
			} catch (Exception exception) {
				if (attempt >= maxAttempts) {
					logger.warn("Gửi Discord webhook thất bại: " + exception.getMessage());
					return false;
				}

				logger.warn("Gửi Discord webhook thất bại (thử lại " + (attempt + 1) + "/" + maxAttempts + "): " + exception.getMessage());
				if (retryDelayMs > 0) {
					try {
						TimeUnit.MILLISECONDS.sleep(retryDelayMs);
					} catch (InterruptedException interruptedException) {
						Thread.currentThread().interrupt();
						return false;
					}
				}
			}
		}

		return false;
	}

	private List<URI> parseWebhookUris(List<String> urls) {
		Set<URI> output = new LinkedHashSet<>();
		if (urls == null) {
			return List.of();
		}

		for (String url : urls) {
			if (url == null || url.isBlank()) {
				continue;
			}

			try {
				output.add(URI.create(url.trim()));
			} catch (IllegalArgumentException exception) {
				logger.warn("Bỏ qua webhook URL không hợp lệ: " + url);
			}
		}

		return new ArrayList<>(output);
	}

	@Override
	public void close() {
		logger.audit("Đã đóng Discord webhook gateway.");
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
}
