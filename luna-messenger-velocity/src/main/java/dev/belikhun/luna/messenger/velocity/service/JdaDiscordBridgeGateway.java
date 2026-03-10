package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

	public JdaDiscordBridgeGateway(
		LunaLogger logger,
		VelocityMessengerConfig.DiscordBotConfig config,
		VelocityMessengerConfig.DiscordRetryConfig retryConfig,
		InboundHandler inboundHandler
	) throws Exception {
		this.logger = logger.scope("DiscordBridge");
		this.config = config;
		this.inboundHandler = inboundHandler;
		this.maxAttempts = retryConfig == null ? 3 : Math.max(1, retryConfig.maxAttempts());
		this.retryDelayMs = retryConfig == null ? 500 : Math.max(0, retryConfig.delayMs());
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
	public void publish(DiscordOutboundMessage outboundMessage) {
		if (outboundMessage == null) {
			return;
		}

		String channelId = Objects.requireNonNull(config.channelId(), "discord.bot.channel-id");
		TextChannel channel = jda.getTextChannelById(channelId);
		if (channel == null) {
			logger.warn("Không tìm thấy Discord channel với id=" + channelId);
			return;
		}

		if (outboundMessage.embed() != null) {
			EmbedBuilder embedBuilder = new EmbedBuilder();
			DiscordOutboundMessage.Embed embed = outboundMessage.embed();
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

			publishWithRetry(channel, outboundMessage, embedBuilder.build(), 1);
			return;
		}

		if (outboundMessage.content() == null || outboundMessage.content().isBlank()) {
			return;
		}

		publishWithRetry(channel, outboundMessage, null, 1);
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
		if (!event.getChannel().getId().equals(config.channelId())) {
			return;
		}

		if (config.ignoreBotMessages() && message.getAuthor().isBot()) {
			return;
		}

		String content = message.getContentDisplay();
		if ((content == null || content.isBlank()) && !message.getEmbeds().isEmpty()) {
			content = message.getEmbeds().get(0).getDescription();
		}
		if (content == null || content.isBlank()) {
			return;
		}

		inboundHandler.handle(new DiscordInboundMessage(
			message.getAuthor().getName(),
			content,
			config.sourceId(),
			message.getId(),
			message.getAuthor().getId()
		));
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
