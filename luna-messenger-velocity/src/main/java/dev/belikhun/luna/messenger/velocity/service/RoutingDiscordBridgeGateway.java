package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

public final class RoutingDiscordBridgeGateway implements DiscordBridgeGateway {
	private final LunaLogger logger;
	private final DiscordBridgeGateway webhookGateway;
	private final DiscordBridgeGateway botGateway;

	public RoutingDiscordBridgeGateway(
		LunaLogger logger,
		DiscordBridgeGateway webhookGateway,
		DiscordBridgeGateway botGateway
	) {
		this.logger = logger.scope("DiscordBridge");
		this.webhookGateway = webhookGateway;
		this.botGateway = botGateway;
	}

	@Override
	public boolean publish(DiscordOutboundMessage message) {
		if (message == null) {
			return false;
		}

		if (webhookGateway != null) {
			boolean webhookSent = webhookGateway.publish(message);
			if (webhookSent) {
				return true;
			}

			if (botGateway != null) {
				logger.warn("Discord webhook gửi thất bại, fallback outbound sang bot.");
				return botGateway.publish(message);
			}

			return false;
		}

		if (botGateway != null) {
			logger.debug("Discord webhook chưa sẵn sàng, fallback outbound sang bot.");
			return botGateway.publish(message);
		}

		return false;
	}

	@Override
	public void close() {
		if (webhookGateway != null) {
			webhookGateway.close();
		}
		if (botGateway != null) {
			botGateway.close();
		}
	}
}
