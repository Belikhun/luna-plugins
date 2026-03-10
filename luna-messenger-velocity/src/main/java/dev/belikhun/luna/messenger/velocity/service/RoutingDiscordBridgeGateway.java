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
	public void publish(DiscordOutboundMessage message) {
		if (message == null) {
			return;
		}

		DiscordOutboundMessage.DispatchType dispatchType = message.dispatchType() == null
			? DiscordOutboundMessage.DispatchType.PLAYER_CHAT
			: message.dispatchType();

		if (dispatchType == DiscordOutboundMessage.DispatchType.BROADCAST) {
			if (botGateway != null) {
				botGateway.publish(message);
				return;
			}
			if (webhookGateway != null) {
				logger.debug("Discord bot chưa sẵn sàng, fallback broadcast sang webhook.");
				webhookGateway.publish(message);
			}
			return;
		}

		if (webhookGateway != null) {
			webhookGateway.publish(message);
			return;
		}
		if (botGateway != null) {
			logger.debug("Discord webhook chưa sẵn sàng, fallback player chat sang bot.");
			botGateway.publish(message);
		}
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
