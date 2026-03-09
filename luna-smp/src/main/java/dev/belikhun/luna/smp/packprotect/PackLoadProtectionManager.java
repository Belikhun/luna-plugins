package dev.belikhun.luna.smp.packprotect;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PackLoadProtectionManager {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final String ACTIVE_CHAT = "<yellow>⚠ Bạn đang được bảo vệ tạm thời khi tải resource pack. PvP, quái và sát thương môi trường đã bị chặn.</yellow>";
	private static final String INACTIVE_CHAT = "<green>✔ Đã tắt bảo vệ tạm thời. Bạn có thể nhận sát thương bình thường.</green>";
	private static final String BOSSBAR_TEXT = "§6🛡 Bảo vệ tạm thời: §fĐang tải resource pack";

	private final LunaLogger logger;
	private final Map<UUID, BossBar> bossBars;
	private final Map<UUID, Boolean> protectedStates;

	public PackLoadProtectionManager(LunaLogger logger) {
		this.logger = logger.scope("PackProtect");
		this.bossBars = new ConcurrentHashMap<>();
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
			hideBossBar(playerId, player);
			if (existed && player.isOnline()) {
				player.sendMessage(MM.deserialize(INACTIVE_CHAT));
			}
		} else {
			BossBar bar = bossBars.remove(playerId);
			if (bar != null) {
				bar.removeAll();
			}
		}
		if (existed) {
			logger.audit("Đã tắt bảo vệ pack cho " + playerName + " (" + playerId + ").");
		}
	}

	public void clearOnQuit(Player player) {
		UUID playerId = player.getUniqueId();
		protectedStates.remove(playerId);
		hideBossBar(playerId, player);
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
		for (BossBar bossBar : bossBars.values()) {
			bossBar.removeAll();
		}
		bossBars.clear();
		protectedStates.clear();
	}

	private void showBossBar(Player player) {
		BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), key -> Bukkit.createBossBar(BOSSBAR_TEXT, BarColor.YELLOW, BarStyle.SOLID));
		bar.setTitle(BOSSBAR_TEXT);
		bar.setColor(BarColor.YELLOW);
		bar.setStyle(BarStyle.SOLID);
		bar.setProgress(1.0D);
		bar.addPlayer(player);
		bar.setVisible(true);
	}

	private void hideBossBar(UUID playerId, Player player) {
		BossBar bar = bossBars.remove(playerId);
		if (bar == null) {
			return;
		}
		bar.removePlayer(player);
		bar.removeAll();
	}
}
