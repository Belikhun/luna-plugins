package dev.belikhun.luna.messenger.velocity.service;

public interface DiscordBridgeGateway {
	boolean publish(DiscordOutboundMessage message);

	void close();
}
