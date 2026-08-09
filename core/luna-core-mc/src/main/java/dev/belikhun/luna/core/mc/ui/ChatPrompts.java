package dev.belikhun.luna.core.mc.ui;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type the new price in chat": a screen asking a player for a line of text.
 *
 * A chest menu has no text field, so every luna screen that needs one closes
 * itself, waits for the player's next chat line and swallows it. Paper does this
 * by cancelling {@code AsyncChatEvent}; the mod loaders each have their own
 * chat event, so this holds who is waiting and the platform's bootstrap feeds it
 * from whichever event that loader fires. One store, so a second module asking
 * for input cannot disagree with the first about whose turn it is.
 *
 * The answer is delivered on the server thread. Chat arrives off it, and every
 * caller goes straight back to opening a menu or writing an item, neither of
 * which is safe from a netty thread.
 */
public final class ChatPrompts {
	private final MinecraftServer server;
	private final Map<UUID, Consumer<String>> waiting;

	public ChatPrompts(MinecraftServer server) {
		this.server = server;
		this.waiting = new ConcurrentHashMap<>();
	}

	/**
	 * Take the player's next chat line instead of broadcasting it.
	 *
	 * A second call replaces the first: a player who opened one prompt and then
	 * another is answering the one they can see.
	 */
	public void await(ServerPlayer player, Consumer<String> answer) {
		if (player == null || answer == null) {
			return;
		}

		waiting.put(player.getUUID(), answer);
	}

	/** Stop waiting on this player, delivering nothing. */
	public void cancel(UUID playerId) {
		if (playerId != null) {
			waiting.remove(playerId);
		}
	}

	public boolean isWaiting(UUID playerId) {
		return playerId != null && waiting.containsKey(playerId);
	}

	/**
	 * Offer a chat line to whoever is waiting for it.
	 *
	 * @return whether the line was taken, in which case the caller must stop the
	 *         message from being broadcast
	 */
	public boolean consume(UUID playerId, String content) {
		Consumer<String> answer = waiting.remove(playerId);

		if (answer == null) {
			return false;
		}

		String trimmed = content == null ? "" : content.trim();
		server.execute(() -> answer.accept(trimmed));
		return true;
	}
}
