package dev.belikhun.luna.messenger.paper.listener;

import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class PaperChatCaptureListener implements Listener {
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private final PaperMessengerGateway gateway;

	public PaperChatCaptureListener(PaperMessengerGateway gateway) {
		this.gateway = gateway;
	}

	// This is the chat sink: whatever reaches HIGHEST uncancelled becomes network chat and
	// is never shown locally. Anything that wants a player's line for itself (shop search,
	// number selector, help search) must claim it at HIGH or below; at the same priority
	// the winner is whichever plugin enabled first, which is how a search query ended up
	// broadcast as chat.
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event) {
		event.setCancelled(true);
		String message = PLAIN.serialize(event.message());
		gateway.sendChat(event.getPlayer(), message);
	}
}
