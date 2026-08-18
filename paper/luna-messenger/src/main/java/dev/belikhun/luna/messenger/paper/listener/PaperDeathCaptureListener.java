package dev.belikhun.luna.messenger.paper.listener;

import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Forward a death to the proxy so it reaches the Discord channels.
 *
 * The rendered sentence is what travels, not the damage source: vanilla builds
 * it from the source, the killer's display name and the item they were holding,
 * a mod adds its own, and none of those exist on the proxy. Reading what the
 * server already produced is also what makes a modded death message work without
 * the proxy knowing the mod exists.
 *
 * **Read at MONITOR, and nothing is cancelled.** Announcing is not the same as
 * owning the event - a plugin ahead of us may clear the message to suppress the
 * announcement, or rewrite it, and whatever it settled on is what should reach
 * Discord. That is also why a cleared message is dropped here rather than
 * replaced with one of our own.
 */
public final class PaperDeathCaptureListener implements Listener {
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private final PaperMessengerGateway gateway;

	public PaperDeathCaptureListener(PaperMessengerGateway gateway) {
		this.gateway = gateway;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		Component message = event.deathMessage();

		if (message == null) {
			return;
		}

		String rendered = PLAIN.serialize(message).trim();

		if (rendered.isEmpty()) {
			return;
		}

		Player player = event.getEntity();

		gateway.sendDeath(player, rendered);
	}
}
