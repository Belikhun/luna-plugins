package dev.belikhun.luna.messenger.velocity.service;

public record DiscordOutboundMessage(
	DispatchType dispatchType,
	String username,
	String avatarUrl,
	String content,
	Embed embed
) {
	public enum DispatchType {
		PLAYER_CHAT,
		BROADCAST
	}

	public record Embed(
		String title,
		String description,
		Integer color,
		String thumbnailUrl,
		String imageUrl
	) {
	}
}
