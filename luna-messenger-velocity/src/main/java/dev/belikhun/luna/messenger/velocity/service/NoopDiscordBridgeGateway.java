package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

public final class NoopDiscordBridgeGateway implements DiscordBridgeGateway {
	private final LunaLogger logger;

	public NoopDiscordBridgeGateway(LunaLogger logger) {
		this.logger = logger.scope("DiscordBridge");
	}

	@Override
	public void publish(DiscordOutboundMessage message) {
		logger.debug("Discord bridge chưa được bật. Bỏ qua outbound message.");
	}

	@Override
	public void close() {
		logger.audit("Đã đóng Discord bridge (noop).");
	}
}
