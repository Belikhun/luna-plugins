package dev.belikhun.luna.vault.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public final class EcoAdminCommand implements SimpleCommand {
	private static final String PERMISSION_ADMIN = "lunavault.admin";
	private static final List<String> SUBCOMMANDS = List.of("get", "set", "add", "take");
	private static final List<String> AMOUNT_SUGGESTIONS = List.of("10.00", "100.00", "1000.00");

	private final VelocityVaultService vaultService;

	public EcoAdminCommand(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!source.hasPermission(PERMISSION_ADMIN)) {
			source.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		String[] args = invocation.arguments();
		if (args.length < 2) {
			sendUsage(source);
			return;
		}

		String subcommand = args[0].trim().toLowerCase();
		if (!SUBCOMMANDS.contains(subcommand)) {
			source.sendRichMessage("<red>❌ Hành động không hợp lệ. Dùng: get, set, add, take.</red>");
			sendUsage(source);
			return;
		}

		Optional<VelocityVaultService.AccountTarget> targetOptional = vaultService.resolveTarget(args[1]);
		if (targetOptional.isEmpty()) {
			source.sendRichMessage("<red>❌ Không tìm thấy tài khoản LunaVault cho người chơi này.</red>");
			return;
		}

		VelocityVaultService.AccountTarget target = targetOptional.get();
		if ("get".equals(subcommand)) {
			vaultService.balance(target.playerId(), target.playerName()).thenAccept(balanceMinor -> {
				source.sendRichMessage("<green>✔ Số dư của <white>" + target.playerName() + "</white>: <gold>" + VaultMoney.formatDefault(balanceMinor) + "</gold>.</green>");
			});
			return;
		}

		if (args.length < 3) {
			source.sendRichMessage("<red>❌ Thiếu số tiền.</red>");
			sendUsage(source);
			return;
		}

		OptionalLong amountMinor = VaultMoney.parseUserInput(args[2]);
		if (amountMinor.isEmpty()) {
			source.sendRichMessage("<red>❌ Số tiền không hợp lệ. Hãy dùng định dạng 00.00.</red>");
			return;
		}

		String actorName = actorName(source);
		Player actorPlayer = source instanceof Player player ? player : null;
		switch (subcommand) {
			case "set" -> vaultService.setBalance(actorPlayer == null ? null : actorPlayer.getUniqueId(), actorName, target.playerId(), target.playerName(), amountMinor.getAsLong(), "eco", "eco set bởi " + actorName)
				.thenAccept(result -> sendMutationResult(source, target, "đặt", result));
			case "add" -> vaultService.deposit(actorPlayer == null ? null : actorPlayer.getUniqueId(), actorName, target.playerId(), target.playerName(), amountMinor.getAsLong(), "eco", "eco add bởi " + actorName)
				.thenAccept(result -> sendMutationResult(source, target, "cộng", result));
			case "take" -> vaultService.withdraw(actorPlayer == null ? null : actorPlayer.getUniqueId(), actorName, target.playerId(), target.playerName(), amountMinor.getAsLong(), "eco", "eco take bởi " + actorName)
				.thenAccept(result -> sendMutationResult(source, target, "trừ", result));
			default -> sendUsage(source);
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		String[] args = invocation.arguments();
		if (args.length == 0 || args.length == 1) {
			String partial = args.length == 0 ? "" : args[0];
			return SUBCOMMANDS.stream()
				.filter(value -> partial.isBlank() || value.regionMatches(true, 0, partial, 0, partial.length()))
				.toList();
		}
		if (args.length == 2) {
			return vaultService.suggestTargets(args[1]);
		}
		if (args.length == 3 && !"get".equalsIgnoreCase(args[0])) {
			String partial = args[2];
			return AMOUNT_SUGGESTIONS.stream()
				.filter(value -> partial.isBlank() || value.regionMatches(true, 0, partial, 0, partial.length()))
				.toList();
		}

		return List.of();
	}

	private void sendMutationResult(CommandSource source, VelocityVaultService.AccountTarget target, String action, dev.belikhun.luna.vault.api.VaultOperationResult result) {
		if (!result.success()) {
			source.sendRichMessage("<red>❌ " + result.message() + "</red>");
			return;
		}

		source.sendRichMessage("<green>✔ Đã " + action + " số dư của <white>" + target.playerName() + "</white>. Số dư mới: <gold>" + VaultMoney.formatDefault(result.balanceMinor()) + "</gold>.</green>");
	}

	private void sendUsage(CommandSource source) {
		source.sendRichMessage("<yellow>ℹ Dùng: /eco get <người_chơi></yellow>");
		source.sendRichMessage("<yellow>ℹ Dùng: /eco set <người_chơi> <số_tiền></yellow>");
		source.sendRichMessage("<yellow>ℹ Dùng: /eco add <người_chơi> <số_tiền></yellow>");
		source.sendRichMessage("<yellow>ℹ Dùng: /eco take <người_chơi> <số_tiền></yellow>");
	}

	private String actorName(CommandSource source) {
		return source instanceof Player player ? player.getUsername() : "CONSOLE";
	}
}
