package dev.belikhun.luna.vault.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.LegacyBalanceImportService;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public final class EcoAdminCommand implements SimpleCommand {
	private static final String PERMISSION_ADMIN = "lunavault.admin";
	private static final List<String> SUBCOMMANDS = List.of("get", "set", "add", "take", "importbalances");
	private static final List<String> AMOUNT_SUGGESTIONS = List.of("10.00", "100.00", "1000.00");
	private static final List<String> IMPORT_FLAGS = List.of("--apply", "--overwrite", "--include-zero");

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
			source.sendRichMessage("<red>❌ Hành động không hợp lệ. Dùng: get, set, add, take, importbalances.</red>");
			sendUsage(source);
			return;
		}

		if ("importbalances".equals(subcommand)) {
			handleImportBalances(source, args);
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
				source.sendRichMessage("<green>✔ Số dư của <white>" + target.playerName() + "</white>: " + LunaCoreVelocity.services().moneyFormat().formatMinor(balanceMinor, VaultMoney.SCALE) + "<green>.</green>");
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
		if (args.length >= 2 && "importbalances".equalsIgnoreCase(args[0])) {
			String partial = args[args.length - 1];
			return IMPORT_FLAGS.stream()
				.filter(value -> partial.isBlank() || value.regionMatches(true, 0, partial, 0, partial.length()))
				.toList();
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

		source.sendRichMessage("<green>✔ Đã " + action + " số dư của <white>" + target.playerName() + "</white>. Số dư mới: " + LunaCoreVelocity.services().moneyFormat().formatMinor(result.balanceMinor(), VaultMoney.SCALE) + "<green>.</green>");
	}

	private void sendUsage(CommandSource source) {
		source.sendRichMessage(CommandStrings.usage("/eco", CommandStrings.literal("get"), CommandStrings.required("người_chơi", "text")));
		source.sendRichMessage(CommandStrings.usage("/eco", CommandStrings.literal("set"), CommandStrings.required("người_chơi", "text"), CommandStrings.required("số_tiền", "number")));
		source.sendRichMessage(CommandStrings.usage("/eco", CommandStrings.literal("add"), CommandStrings.required("người_chơi", "text"), CommandStrings.required("số_tiền", "number")));
		source.sendRichMessage(CommandStrings.usage("/eco", CommandStrings.literal("take"), CommandStrings.required("người_chơi", "text"), CommandStrings.required("số_tiền", "number")));
		source.sendRichMessage(CommandStrings.usage("/eco", CommandStrings.literal("importbalances"), CommandStrings.required("đường_dẫn_balances.yml", "path"), CommandStrings.optional("--apply", "flag"), CommandStrings.optional("--overwrite", "flag"), CommandStrings.optional("--include-zero", "flag")));
	}

	private void handleImportBalances(CommandSource source, String[] args) {
		if (args.length < 2) {
			source.sendRichMessage("<red>❌ Thiếu đường dẫn tới file balances.yml.</red>");
			sendUsage(source);
			return;
		}

		StringBuilder pathBuilder = new StringBuilder();
		boolean applyChanges = false;
		boolean overwriteExisting = false;
		boolean includeZeroBalances = false;
		for (int index = 1; index < args.length; index++) {
			String token = args[index].trim();
			if (token.isBlank()) {
				continue;
			}

			switch (token.toLowerCase()) {
				case "--apply" -> applyChanges = true;
				case "--overwrite" -> overwriteExisting = true;
				case "--include-zero" -> includeZeroBalances = true;
				default -> {
					if (pathBuilder.length() > 0) {
						pathBuilder.append(' ');
					}
					pathBuilder.append(token);
				}
			}
		}

		if (pathBuilder.length() <= 0) {
			source.sendRichMessage("<red>❌ Không thể đọc đường dẫn file import.</red>");
			return;
		}

		Path sourcePath;
		try {
			sourcePath = Path.of(pathBuilder.toString());
		} catch (InvalidPathException exception) {
			source.sendRichMessage("<red>❌ Đường dẫn không hợp lệ: " + exception.getInput() + "</red>");
			return;
		}

		LegacyBalanceImportService.ImportOptions options = new LegacyBalanceImportService.ImportOptions(applyChanges, overwriteExisting, includeZeroBalances);
		LegacyBalanceImportService.ImportSummary summary;
		try {
			summary = vaultService.legacyBalanceImportService().importBalances(sourcePath, options);
		} catch (IllegalArgumentException exception) {
			source.sendRichMessage("<red>❌ " + exception.getMessage() + "</red>");
			return;
		} catch (RuntimeException exception) {
			source.sendRichMessage("<red>❌ Import thất bại: " + exception.getMessage() + "</red>");
			return;
		}

		source.sendRichMessage("<yellow>ℹ File nguồn:</yellow> <white>" + summary.sourcePath() + "</white>");
		source.sendRichMessage("<yellow>ℹ Tổng dòng:</yellow> <white>" + summary.totalEntries() + "</white> <gray>| hợp lệ để import: " + summary.candidateEntries() + "</gray>");
		source.sendRichMessage("<yellow>ℹ Tổng số dư hợp lệ:</yellow> " + LunaCoreVelocity.services().moneyFormat().formatMinor(summary.candidateMinor(), VaultMoney.SCALE) + "<gray>.</gray>");
		source.sendRichMessage("<yellow>ℹ Bỏ qua:</yellow> <white>existing=" + summary.existingSkipped() + "</white> <gray>| zero=" + summary.zeroSkipped() + " | invalidUuid=" + summary.invalidUuid() + " | invalidAmount=" + summary.invalidAmount() + "</gray>");

		if (!summary.options().applyChanges()) {
			source.sendRichMessage("<green>✔ Đây là dry-run. Chưa có dữ liệu nào được ghi vào LunaVault.</green>");
			source.sendRichMessage("<yellow>ℹ Chạy lại với </yellow>" + CommandStrings.syntaxRaw("/eco importbalances " + summary.sourcePath() + " --apply") + "<yellow> để thực thi.</yellow>");
			if (summary.existingSkipped() > 0) {
				source.sendRichMessage("<yellow>ℹ Thêm </yellow>" + CommandStrings.syntaxRaw("--overwrite") + "<yellow> nếu muốn ghi đè tài khoản đã tồn tại.</yellow>");
			}
			return;
		}

		if (summary.insertedEntries() > 0 || summary.updatedEntries() > 0) {
			vaultService.invalidateBackendCaches();
		}

		source.sendRichMessage("<green>✔ Import hoàn tất:</green> <white>inserted=" + summary.insertedEntries() + "</white> <gray>| updated=" + summary.updatedEntries() + "</gray>");
		source.sendRichMessage("<green>✔ Tổng số dư đã ghi:</green> " + LunaCoreVelocity.services().moneyFormat().formatMinor(summary.appliedMinor(), VaultMoney.SCALE) + "<gray> | delta=" + LunaCoreVelocity.services().moneyFormat().formatMinor(summary.deltaMinor(), VaultMoney.SCALE) + "</gray>");
	}

	private String actorName(CommandSource source) {
		return source instanceof Player player ? player.getUsername() : "CONSOLE";
	}
}
