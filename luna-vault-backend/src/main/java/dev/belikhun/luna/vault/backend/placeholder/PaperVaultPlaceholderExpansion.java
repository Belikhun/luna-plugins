package dev.belikhun.luna.vault.backend.placeholder;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.backend.service.PaperVaultGateway;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class PaperVaultPlaceholderExpansion extends PlaceholderExpansion {
	private final JavaPlugin plugin;
	private final PaperVaultGateway gateway;
	private final ConfigStore coreConfig;
	private final long timeoutMillis;

	public PaperVaultPlaceholderExpansion(JavaPlugin plugin, PaperVaultGateway gateway, ConfigStore coreConfig, long timeoutMillis) {
		this.plugin = plugin;
		this.gateway = gateway;
		this.coreConfig = coreConfig;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
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

		try {
			VaultPlayerSnapshot snapshot = gateway.snapshot(player.getUniqueId(), player.getName()).get(timeoutMillis + 250L, TimeUnit.MILLISECONDS);
			if (normalized.equals("rank")) {
				return String.valueOf(snapshot.rank());
			}
			return Formatters.money(coreConfig, snapshot.balanceMinor(), VaultMoney.SCALE);
		} catch (Exception exception) {
			return normalized.equals("rank") ? "0" : Formatters.money(coreConfig, 0D);
		}
	}
}
