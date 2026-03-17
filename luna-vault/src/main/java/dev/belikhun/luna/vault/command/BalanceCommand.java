package dev.belikhun.luna.vault.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.List;

public final class BalanceCommand implements SimpleCommand {
	private final VelocityVaultService vaultService;

	public BalanceCommand(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng trong game.</red>");
			return;
		}

		if (invocation.arguments().length > 0) {
			player.sendRichMessage("<yellow>ℹ Dùng: /balance</yellow>");
			return;
		}

		vaultService.balance(player.getUniqueId(), player.getUsername()).thenAccept(balanceMinor ->
			player.sendRichMessage("<green>✔ Số dư hiện tại của bạn: " + LunaCoreVelocity.services().moneyFormat().formatMinor(balanceMinor, VaultMoney.SCALE) + "<green>.</green>")
		);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}
}
