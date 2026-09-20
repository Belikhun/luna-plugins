package dev.belikhun.luna.vault.service;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.config.LunaYamlConfig;
import dev.belikhun.luna.vault.api.VaultMoney;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

public record VelocityVaultConfig(
	boolean transactionLoggingEnabled,
	LargeTransactionAlertConfig largeTransactionAlert,
	int ledgerThreads
) {
	public static final int DEFAULT_LEDGER_THREADS = 4;

	public static VelocityVaultConfig load(Path path) {
		Map<String, Object> root = LunaYamlConfig.loadMap(path);
		Map<String, Object> logging = ConfigValues.map(root, "logging");
		Map<String, Object> transactions = ConfigValues.map(logging, "transactions");
		Map<String, Object> largeAlerts = ConfigValues.map(transactions, "large-alerts");
		Map<String, Object> ledger = ConfigValues.map(root, "ledger");

		return new VelocityVaultConfig(
			ConfigValues.booleanValue(transactions, "enabled", true),
			new LargeTransactionAlertConfig(
				ConfigValues.booleanValue(largeAlerts, "enabled", true),
				parseThreshold(largeAlerts.get("threshold"), 1_000_000L),
				ConfigValues.string(largeAlerts, "permission", "lunavault.alert")
			),
			Math.max(1, Math.min(32, ConfigValues.intValue(ledger, "threads", DEFAULT_LEDGER_THREADS)))
		);
	}

	private static long parseThreshold(Object rawValue, long fallbackMinor) {
		OptionalLong parsed = VaultMoney.parseUserInput(String.valueOf(rawValue == null ? "" : rawValue));
		return parsed.isPresent() && parsed.getAsLong() > 0L ? parsed.getAsLong() : fallbackMinor;
	}

	public record LargeTransactionAlertConfig(
		boolean enabled,
		long thresholdMinor,
		String permission
	) {
	}
}
