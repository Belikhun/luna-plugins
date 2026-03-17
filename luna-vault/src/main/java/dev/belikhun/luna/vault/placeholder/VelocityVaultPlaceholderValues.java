package dev.belikhun.luna.vault.placeholder;

import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.UUID;

final class VelocityVaultPlaceholderValues {
	private final VelocityVaultService vaultService;

	VelocityVaultPlaceholderValues(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	String balance(UUID playerId, String playerName) {
		return LunaCoreVelocity.services().moneyFormat().formatMinor(snapshot(playerId, playerName).balanceMinor(), VaultMoney.SCALE);
	}

	String rank(UUID playerId, String playerName) {
		if (playerId == null) {
			return "";
		}

		return String.valueOf(snapshot(playerId, playerName).rank());
	}

	private VaultPlayerSnapshot snapshot(UUID playerId, String playerName) {
		if (playerId == null) {
			return VaultPlayerSnapshot.empty(null, playerName);
		}

		return vaultService.snapshot(playerId, playerName).join();
	}
}
