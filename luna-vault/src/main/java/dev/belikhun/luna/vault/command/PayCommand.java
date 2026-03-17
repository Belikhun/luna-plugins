package dev.belikhun.luna.vault.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

public final class PayCommand implements SimpleCommand {
	private final ProxyServer proxyServer;
	private final VelocityVaultService vaultService;

	public PayCommand(ProxyServer proxyServer, VelocityVaultService vaultService) {
		this.proxyServer = proxyServer;
		this.vaultService = vaultService;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!(source instanceof Player sender)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng trong game.</red>");
			return;
		}

		String[] args = invocation.arguments();
		if (args.length < 2) {
			sender.sendRichMessage("<yellow>ℹ Dùng: /pay <người_chơi> <số_tiền> [ghi_chú]</yellow>");
			return;
		}

		Optional<Player> target = vaultService.findOnlinePlayer(args[0]);
		if (target.isEmpty()) {
			sender.sendRichMessage("<red>❌ Không tìm thấy người chơi đang online trên mạng.</red>");
			return;
		}

		OptionalLong amountMinor = VaultMoney.parseUserInput(args[1]);
		if (amountMinor.isEmpty() || amountMinor.getAsLong() <= 0L) {
			sender.sendRichMessage("<red>❌ Số tiền không hợp lệ. Hãy dùng định dạng 00.00.</red>");
			return;
		}

		String details = args.length > 2 ? Arrays.stream(args).skip(2).collect(Collectors.joining(" ")) : null;
		vaultService.transfer(
			sender.getUniqueId(),
			sender.getUsername(),
			target.get().getUniqueId(),
			target.get().getUsername(),
			amountMinor.getAsLong(),
			"pay",
			details
		).thenAccept(result -> {
			if (!result.success()) {
				sender.sendRichMessage("<red>❌ " + result.message() + "</red>");
				return;
			}

			String formattedAmount = VaultMoney.formatDefault(amountMinor.getAsLong());
			sender.sendRichMessage("<green>✔ Đã chuyển <gold>" + formattedAmount + "</gold> cho <white>" + target.get().getUsername() + "</white>.</green>");
			target.get().sendRichMessage("<green>✔ Bạn vừa nhận <gold>" + formattedAmount + "</gold> từ <white>" + sender.getUsername() + "</white>.</green>");
		});
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0 || args.length == 1) {
			String partial = args.length == 0 ? "" : args[0];
			return proxyServer.getAllPlayers().stream()
				.map(Player::getUsername)
				.filter(name -> partial.isBlank() || name.regionMatches(true, 0, partial, 0, partial.length()))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(20)
				.toList();
		}

		return List.of();
	}
}
