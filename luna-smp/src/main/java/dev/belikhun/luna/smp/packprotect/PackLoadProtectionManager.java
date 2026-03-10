package dev.belikhun.luna.smp.packprotect;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PackLoadProtectionManager {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final long RELEASE_BAR_HIDE_TICKS = 20L * 5L;
	private static final String ACTIVE_CHAT = "<yellow>⚠ Bạn đang được bảo vệ tạm thời khi tải resource pack. PvP, quái và sát thương môi trường đã bị chặn.</yellow>";
	private static final String INACTIVE_CHAT = "<green>✔ Đã tắt bảo vệ tạm thời. Bạn có thể nhận sát thương bình thường.</green>";
	private static final String BOSSBAR_TEXT = "§6🛡 Bảo vệ tạm thời: §fĐang tải resource pack";
	private static final String RELEASE_BOSSBAR_TEXT = "§e⚠ Bảo vệ đã tắt";

	private final Plugin plugin;
	private final LunaLogger logger;
	private final Map<UUID, BossBar> bossBars;
	private final Map<UUID, BukkitTask> bossBarHideTasks;
	private final Map<UUID, Boolean> protectedStates;

	public PackLoadProtectionManager(Plugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger.scope("PackProtect");
		this.bossBars = new ConcurrentHashMap<>();
		this.bossBarHideTasks = new ConcurrentHashMap<>();
		this.protectedStates = new ConcurrentHashMap<>();
	}

	public void enable(UUID playerId, String playerName) {
		boolean wasProtected = protectedStates.put(playerId, Boolean.TRUE) != null;
		Player player = Bukkit.getPlayer(playerId);
		if (player != null && player.isOnline()) {
			showBossBar(player);
			if (!wasProtected) {
				player.sendMessage(MM.deserialize(ACTIVE_CHAT));
			}
		}
		if (!wasProtected) {
			logger.audit("Đã bật bảo vệ pack cho " + playerName + " (" + playerId + ").");
		}
	}

	public void disable(UUID playerId, String playerName) {
		boolean existed = protectedStates.remove(playerId) != null;
		Player player = Bukkit.getPlayer(playerId);
		if (player != null) {
			showReleaseBar(player);
			if (existed && player.isOnline()) {
				player.sendMessage(MM.deserialize(INACTIVE_CHAT));
			}
		} else {
			hideBossBarNow(playerId);
		}
		if (existed) {
			logger.audit("Đã tắt bảo vệ pack cho " + playerName + " (" + playerId + ").");
		}
	}

	public void clearOnQuit(Player player) {
		UUID playerId = player.getUniqueId();
		protectedStates.remove(playerId);
		hideBossBarNow(playerId);
	}

	public void restoreBossBarIfProtected(Player player) {
		if (!isProtected(player.getUniqueId())) {
			return;
		}
		showBossBar(player);
		player.sendMessage(MM.deserialize(ACTIVE_CHAT));
	}

	public boolean isProtected(UUID playerId) {
		return protectedStates.containsKey(playerId);
	}

	public void close() {
		for (BukkitTask task : bossBarHideTasks.values()) {
			task.cancel();
		}
		bossBarHideTasks.clear();

		for (BossBar bossBar : bossBars.values()) {
			bossBar.removeAll();
		}
		bossBars.clear();
		protectedStates.clear();
	}

	private void showBossBar(Player player) {
		cancelBossBarHide(player.getUniqueId());

		BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), key -> Bukkit.createBossBar(BOSSBAR_TEXT, BarColor.YELLOW, BarStyle.SOLID));
		bar.setTitle(BOSSBAR_TEXT);
		bar.setColor(BarColor.YELLOW);
		bar.setStyle(BarStyle.SOLID);
		bar.setProgress(1.0D);
		if (!bar.getPlayers().contains(player)) {
			bar.addPlayer(player);
		}
		bar.setVisible(true);
	}

	private void showReleaseBar(Player player) {
		UUID playerId = player.getUniqueId();
		BossBar bar = bossBars.computeIfAbsent(playerId, key -> Bukkit.createBossBar(RELEASE_BOSSBAR_TEXT, BarColor.YELLOW, BarStyle.SOLID));
		bar.setTitle(RELEASE_BOSSBAR_TEXT);
		bar.setColor(BarColor.YELLOW);
		bar.setStyle(BarStyle.SOLID);
		bar.setVisible(true);
		bar.setProgress(1.0D);
		if (!bar.getPlayers().contains(player)) {
			bar.addPlayer(player);
		}

		cancelBossBarHide(playerId);
		BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			private long remaining = RELEASE_BAR_HIDE_TICKS;

			@Override
			public void run() {
				remaining -= 1L;
				double progress = Math.max(0.0D, (double) remaining / (double) RELEASE_BAR_HIDE_TICKS);
				bar.setProgress(progress);

				if (remaining <= 0L) {
					hideBossBarNow(playerId);
				}
			}
		}, 1L, 1L);
		bossBarHideTasks.put(playerId, task);
	}

	private void hideBossBarNow(UUID playerId) {
		cancelBossBarHide(playerId);
		BossBar bar = bossBars.remove(playerId);
		if (bar == null) {
			return;
		}
		bar.removeAll();
		bar.setVisible(false);
	}

	private void cancelBossBarHide(UUID playerId) {
		BukkitTask task = bossBarHideTasks.remove(playerId);
		if (task != null) {
			task.cancel();
		}
	}
}
