package dev.belikhun.luna.messenger.velocity.service;

public record DiscordInboundMessage(
	String authorName,
	String authorUsername,
	String authorNickname,
	String message,
	String source,
	String messageId,
	String authorId
) {
}
