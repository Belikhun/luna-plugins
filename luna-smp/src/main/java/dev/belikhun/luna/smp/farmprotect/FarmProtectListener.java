package dev.belikhun.luna.smp.farmprotect;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class FarmProtectListener implements Listener {
	@EventHandler(ignoreCancelled = true)
	public void onEntityInteract(EntityInteractEvent event) {
		if (event.getBlock().getType() != Material.FARMLAND) {
			return;
		}

		event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.PHYSICAL) {
			return;
		}

		if (event.getClickedBlock() == null) {
			return;
		}

		if (event.getClickedBlock().getType() != Material.FARMLAND) {
			return;
		}

		event.setCancelled(true);
	}
}

