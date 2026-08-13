package dev.belikhun.luna.core.mc12.serverselector;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The selector's own event wiring: forgetting a player who left, and getting the
 * controller attached to the plugin-message bus in the first place.
 *
 * **The attach cannot happen when the core starts.** `lunacoremessaging` loads
 * after `lunacore`, so the bus does not exist yet during the core's own start
 * handler - and the selector's only trigger is an *incoming* message, so a
 * controller that never attaches never runs and never retries. Ticking until the
 * bus appears is what closes that loop.
 */
public final class SelectorCleanupListener {
	private final ServerSelectorController selector;

	private boolean attached;

	public SelectorCleanupListener(ServerSelectorController selector) {
		this.selector = selector;
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (attached || event.phase != TickEvent.Phase.END) {
			return;
		}

		selector.ensureMessagingAttached();
		attached = selector.isMessagingAttached();
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.player instanceof EntityPlayerMP) {
			selector.cleanupPlayer(((EntityPlayerMP) event.player).getUniqueID());
		}
	}
}
