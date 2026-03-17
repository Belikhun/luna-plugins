package dev.belikhun.luna.vault.placeholder;

import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.UUID;

final class VelocityVaultPlaceholderValues {
	private final VelocityVaultService vaultService;

	VelocityVaultPlaceholderValues(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	String balance(UUID playerId, String playerName) {
		if (playerId == null) {
			return "";
		}

		long balanceMinor = vaultService.balance(playerId, playerName).join();
		return LunaCoreVelocity.services().moneyFormat().formatMinor(balanceMinor, VaultMoney.SCALE);
	}
}
