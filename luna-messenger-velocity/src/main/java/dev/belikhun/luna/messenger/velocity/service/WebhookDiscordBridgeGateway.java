package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WebhookDiscordBridgeGateway implements DiscordBridgeGateway {
	private final LunaLogger logger;
	private final URI webhookUri;
	private final HttpClient httpClient;
	private final int maxAttempts;
	private final int retryDelayMs;
	private final ExecutorService retryExecutor;

	public WebhookDiscordBridgeGateway(
		LunaLogger logger,
		String webhookUrl,
		VelocityMessengerConfig.DiscordRetryConfig retryConfig
	) {
		this.logger = logger.scope("DiscordBridge");
		this.webhookUri = URI.create(webhookUrl);
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.maxAttempts = retryConfig == null ? 3 : Math.max(1, retryConfig.maxAttempts());
		this.retryDelayMs = retryConfig == null ? 500 : Math.max(0, retryConfig.delayMs());
		this.retryExecutor = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-discord-webhook-retry");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public void publish(DiscordOutboundMessage message) {
		if ((message.content() == null || message.content().isBlank()) && message.embed() == null) {
			return;
		}

		String payload = toWebhookJson(message);
		publishWithRetry(payload, 1);
	}

	private void publishWithRetry(String payload, int attempt) {
		HttpRequest request = HttpRequest.newBuilder(webhookUri)
			.timeout(Duration.ofSeconds(5))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
			.build();

		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.whenComplete((response, throwable) -> {
				if (throwable != null) {
					handleRetry("Gửi Discord webhook thất bại", throwable.getMessage(), payload, attempt);
					return;
				}

				int code = response.statusCode();
				if (code >= 200 && code < 300) {
					return;
				}

				if (attempt >= maxAttempts || (code != 429 && code < 500)) {
					logger.warn("Discord webhook trả về mã lỗi " + code + ": " + response.body());
					return;
				}

				handleRetry("Discord webhook trả về mã lỗi " + code, response.body(), payload, attempt);
			});
	}

	private void handleRetry(String reason, String detail, String payload, int attempt) {
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
				publishWithRetry(payload, nextAttempt);
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
			}
		}, retryExecutor);
	}

	@Override
	public void close() {
		retryExecutor.shutdownNow();
		logger.audit("Đã đóng Discord webhook gateway.");
	}

	private String toWebhookJson(DiscordOutboundMessage message) {
		StringBuilder json = new StringBuilder("{");
		appendField(json, "username", message.username());
		appendField(json, "avatar_url", message.avatarUrl());
		appendField(json, "content", message.content());
		if (message.embed() != null) {
			appendComma(json);
			json.append("\"embeds\":[").append(embedJson(message.embed())).append("]");
		}
		json.append("}");
		return json.toString();
	}

	private String embedJson(DiscordOutboundMessage.Embed embed) {
		StringBuilder json = new StringBuilder("{");
		appendField(json, "title", embed.title());
		appendField(json, "description", embed.description());
		if (embed.color() != null) {
			appendComma(json);
			json.append("\"color\":").append(embed.color());
		}
		if (embed.thumbnailUrl() != null && !embed.thumbnailUrl().isBlank()) {
			appendComma(json);
			json.append("\"thumbnail\":{\"url\":\"").append(escapeJson(embed.thumbnailUrl())).append("\"}");
		}
		if (embed.imageUrl() != null && !embed.imageUrl().isBlank()) {
			appendComma(json);
			json.append("\"image\":{\"url\":\"").append(escapeJson(embed.imageUrl())).append("\"}");
		}
		json.append("}");
		return json.toString();
	}

	private void appendField(StringBuilder json, String key, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		appendComma(json);
		json.append("\"").append(key).append("\":\"").append(escapeJson(value)).append("\"");
	}

	private void appendComma(StringBuilder json) {
		int length = json.length();
		if (length <= 1) {
			return;
		}
		char last = json.charAt(length - 1);
		if (last != '{' && last != '[' && last != ',') {
			json.append(',');
		}
	}

	private String escapeJson(String value) {
		StringBuilder out = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c);
			}
		}
		return out.toString();
	}
}
