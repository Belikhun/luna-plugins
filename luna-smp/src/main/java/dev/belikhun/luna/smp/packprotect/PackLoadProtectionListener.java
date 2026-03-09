package dev.belikhun.luna.smp.packprotect;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PackLoadProtectionListener implements Listener {
	private final PackLoadProtectionManager protectionManager;

	public PackLoadProtectionListener(PackLoadProtectionManager protectionManager) {
		this.protectionManager = protectionManager;
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}
		if (!protectionManager.isProtected(player.getUniqueId())) {
			return;
		}

		event.setCancelled(true);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		protectionManager.restoreBossBarIfProtected(event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		protectionManager.clearOnQuit(event.getPlayer());
	}
}
