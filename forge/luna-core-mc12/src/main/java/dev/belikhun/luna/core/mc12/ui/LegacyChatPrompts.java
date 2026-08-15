package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.core.mc12.runtime.ServerThreadTasks;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type the new price in chat": a screen asking a player for a line of text.
 *
 * A chest menu has no text field, so every luna screen needing one closes itself,
 * waits for the player's next chat line and swallows it. This is the 1.12.2 half
 * of the same idea the other backends run, and it is registered in the core's
 * service registry rather than owned by a module: one store, so a second module
 * asking for input cannot disagree with the first about whose turn it is.
 *
 * **The line is swallowed, not merely read.** A player typing a price into chat
 * has not said anything to the server, and letting it through would broadcast a
 * number to everyone and log it as speech.
 *
 * `ServerChatEvent` already runs on the server thread, so the answer is delivered
 * straight through {@link ServerThreadTasks}, which is a no-op hop there. It is
 * still routed that way because a caller answering a prompt goes on to open a
 * menu or write an item, and neither is safe from anywhere else.
 */
public final class LegacyChatPrompts {
	private final MinecraftServer server;
	private final Map<UUID, Consumer<String>> waiting;

	public LegacyChatPrompts(MinecraftServer server) {
		this.server = server;
		this.waiting = new ConcurrentHashMap<UUID, Consumer<String>>();
	}

	/**
	 * Take the player's next chat line instead of broadcasting it.
	 *
	 * A second call replaces the first: a player who opened one prompt and then
	 * another is answering the one they can see.
	 */
	public void await(EntityPlayerMP player, Consumer<String> answer) {
		if (player == null || answer == null) {
			return;
		}

		waiting.put(player.getUniqueID(), answer);
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
		final Consumer<String> answer = waiting.remove(playerId);

		if (answer == null) {
			return false;
		}

		final String trimmed = content == null ? "" : content.trim();

		ServerThreadTasks.run(server, new Runnable() {
			@Override
			public void run() {
				answer.accept(trimmed);
			}
		});

		return true;
	}

	/**
	 * Highest priority, so a prompt answer never reaches chat formatting or logging.
	 *
	 * A cancelled event stops the broadcast; other listeners that opted out of
	 * cancelled events never see the line at all, which is the point.
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onServerChat(ServerChatEvent event) {
		if (event.getPlayer() == null) {
			return;
		}

		if (consume(event.getPlayer().getUniqueID(), event.getMessage())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.player instanceof EntityPlayerMP) {
			cancel(((EntityPlayerMP) event.player).getUniqueID());
		}
	}
}
