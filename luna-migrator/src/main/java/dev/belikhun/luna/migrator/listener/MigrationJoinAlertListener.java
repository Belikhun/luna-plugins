package dev.belikhun.luna.migrator.listener;

import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.migrator.service.MigrationEligibilityService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class MigrationJoinAlertListener implements Listener {
	private final JavaPlugin plugin;
	private final MigrationEligibilityService eligibilityService;

	public MigrationJoinAlertListener(JavaPlugin plugin, MigrationEligibilityService eligibilityService) {
		this.plugin = plugin;
		this.eligibilityService = eligibilityService;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (!plugin.getConfig().getBoolean("migration.alert.enabled", true)) {
			return;
		}

		Player player = event.getPlayer();
		String candidateLegacyUsername = player.getName();
		if (!isEligibleForPrompt(player.getUniqueId(), candidateLegacyUsername)) {
			return;
		}

		int repeats = Math.max(1, plugin.getConfig().getInt("migration.alert.repeat-count", 3));
		int intervalSeconds = Math.max(1, plugin.getConfig().getInt("migration.alert.interval-seconds", 10));
		int initialDelaySeconds = Math.max(0, plugin.getConfig().getInt("migration.alert.initial-delay-seconds", 1));

		for (int index = 0; index < repeats; index++) {
			long delay = (long) (initialDelaySeconds + (index * intervalSeconds)) * 20L;
			Bukkit.getScheduler().runTaskLater(plugin, () -> sendPromptIfStillEligible(player), delay);
		}
	}

	private void sendPromptIfStillEligible(Player player) {
		if (!player.isOnline()) {
			return;
		}

		String candidateLegacyUsername = player.getName();
		if (!isEligibleForPrompt(player.getUniqueId(), candidateLegacyUsername)) {
			return;
		}

		player.sendRichMessage("<color:" + LunaPalette.WARNING_300 + ">⚠ Phát hiện dữ liệu cũ có thể migrate. Dùng </color>"
			+ "<color:" + LunaPalette.PRIMARY_300 + "><b>/migrate</b></color>"
			+ "<color:" + LunaPalette.WARNING_300 + "> để xem chi tiết.</color>");
	}

	private boolean isEligibleForPrompt(UUID onlineUuid, String candidateLegacyUsername) {
		return eligibilityService.evaluate(onlineUuid, candidateLegacyUsername).eligible();
	}
}
