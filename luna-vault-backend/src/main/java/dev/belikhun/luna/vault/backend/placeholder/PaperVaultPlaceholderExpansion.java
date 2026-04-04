package dev.belikhun.luna.vault.backend.placeholder;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.backend.service.PaperVaultGateway;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperVaultPlaceholderExpansion extends PlaceholderExpansion implements Listener {
	private static final int MAX_CACHE_ENTRIES = 4096;

	private final JavaPlugin plugin;
	private final PaperVaultGateway gateway;
	private final ConfigStore coreConfig;
	private final long refreshIntervalMillis;
	private final Map<UUID, CachedSnapshot> snapshotCache;
	private final Set<UUID> refreshInFlight;

	public PaperVaultPlaceholderExpansion(JavaPlugin plugin, PaperVaultGateway gateway, ConfigStore coreConfig, long timeoutMillis) {
		this.plugin = plugin;
		this.gateway = gateway;
		this.coreConfig = coreConfig;
		long normalizedTimeout = Math.max(1000L, timeoutMillis);
		this.refreshIntervalMillis = Math.min(5000L, Math.max(500L, normalizedTimeout / 2L));
		this.snapshotCache = new ConcurrentHashMap<>();
		this.refreshInFlight = ConcurrentHashMap.newKeySet();
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		snapshotCache.remove(playerId);
		refreshInFlight.remove(playerId);
	}

	@Override
	public String getIdentifier() {
		return "lunavault";
	}

	@Override
	public String getAuthor() {
		return "Belikhun";
	}

	@Override
	public String getVersion() {
		return plugin.getPluginMeta().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String onRequest(OfflinePlayer player, String params) {
		if (player == null || params == null || params.isBlank()) {
			return "";
		}

		String normalized = params.trim().toLowerCase(Locale.ROOT);
		if (!normalized.equals("balance") && !normalized.equals("rank")) {
			return "";
		}

		UUID playerId = player.getUniqueId();
		if (playerId == null) {
			return fallbackValue(normalized);
		}

		long now = System.currentTimeMillis();
		CachedSnapshot cached = snapshotCache.get(playerId);
		if (cached != null) {
			if (now - cached.cachedAtMillis() > refreshIntervalMillis) {
				refreshSnapshotAsync(playerId, player.getName());
			}
			return formatValue(normalized, cached.snapshot());
		}

		VaultPlayerSnapshot immediate = gateway.cachedSnapshot(playerId, player.getName());
		if (immediate.playerId() != null) {
			cache(playerId, immediate);
			return formatValue(normalized, immediate);
		}

		refreshSnapshotAsync(playerId, player.getName());
		return fallbackValue(normalized);
	}

	private String formatValue(String normalized, VaultPlayerSnapshot snapshot) {
		if (normalized.equals("rank")) {
			return String.valueOf(snapshot.rank());
		}

		return Formatters.money(coreConfig, snapshot.balanceMinor(), VaultMoney.SCALE);
	}

	private String fallbackValue(String normalized) {
		if (normalized.equals("rank")) {
			return "0";
		}

		return Formatters.money(coreConfig, 0D);
	}

	private void refreshSnapshotAsync(UUID playerId, String playerName) {
		if (!refreshInFlight.add(playerId)) {
			return;
		}

		gateway.snapshot(playerId, playerName).whenComplete((snapshot, throwable) -> {
			try {
				if (throwable == null && snapshot != null && snapshot.playerId() != null) {
					cache(playerId, snapshot);
				}
			} finally {
				refreshInFlight.remove(playerId);
			}
		});
	}

	private void cache(UUID playerId, VaultPlayerSnapshot snapshot) {
		if (snapshotCache.size() >= MAX_CACHE_ENTRIES) {
			snapshotCache.clear();
		}

		snapshotCache.put(playerId, new CachedSnapshot(snapshot, System.currentTimeMillis()));
	}

	private record CachedSnapshot(VaultPlayerSnapshot snapshot, long cachedAtMillis) {
	}
}
