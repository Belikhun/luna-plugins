package dev.belikhun.luna.messenger.velocity.service;

public interface DiscordBridgeGateway {
	void publish(DiscordOutboundMessage message);

	void close();
}
