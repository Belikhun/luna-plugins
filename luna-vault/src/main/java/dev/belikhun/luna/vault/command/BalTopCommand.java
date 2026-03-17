package dev.belikhun.luna.vault.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.VaultLeaderboardEntry;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.service.VelocityVaultService;

import java.util.List;

public final class BalTopCommand implements SimpleCommand {
	private static final int PAGE_SIZE = 10;
	private static final int TARGET_WIDTH_PX = 150;
	private static final List<String> PAGE_SUGGESTIONS = List.of("1", "2", "3", "4", "5");

	private final VelocityVaultService vaultService;

	public BalTopCommand(VelocityVaultService vaultService) {
		this.vaultService = vaultService;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] args = invocation.arguments();
		if (args.length > 1) {
			source.sendRichMessage(CommandStrings.usage("/baltop", CommandStrings.optional("trang", "number")));
			return;
		}

		int page = 0;
		if (args.length == 1) {
			try {
				page = Math.max(0, Integer.parseInt(args[0]) - 1);
			} catch (NumberFormatException exception) {
				source.sendRichMessage("<red>❌ Trang phải là số hợp lệ.</red>");
				return;
			}
		}

		VaultLeaderboardPage leaderboard = vaultService.leaderboard(page, PAGE_SIZE).join();
		if (leaderboard.entries().isEmpty()) {
			source.sendRichMessage("<yellow>ℹ Chưa có dữ liệu số dư để xếp hạng.</yellow>");
			return;
		}

		source.sendRichMessage("<color:" + LunaPalette.GOLD_300 + "><b>💰 BẢNG XẾP HẠNG SỐ DƯ</b></color>"
			+ " <color:" + LunaPalette.NEUTRAL_300 + ">Trang <white>" + (leaderboard.page() + 1) + "</white>/<white>" + (leaderboard.maxPage() + 1) + "</white> | Tổng <white>" + leaderboard.totalCount() + "</white></color>");
		for (VaultLeaderboardEntry entry : leaderboard.entries()) {
			source.sendRichMessage(renderLine(entry));
		}

		if (source instanceof Player player) {
			var snapshot = vaultService.snapshot(player.getUniqueId(), player.getUsername()).join();
			source.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300 + ">Hạng của bạn: <white>#" + snapshot.rank() + "</white> | Số dư: </color>"
				+ LunaCoreVelocity.services().moneyFormat().formatMinor(snapshot.balanceMinor(), VaultMoney.SCALE));
		}
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		if (invocation.arguments().length > 1) {
			return List.of();
		}

		String partial = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
		return PAGE_SUGGESTIONS.stream()
			.filter(value -> partial.isBlank() || value.regionMatches(true, 0, partial, 0, partial.length()))
			.toList();
	}

	private String renderLine(VaultLeaderboardEntry entry) {
		String playerName = entry.playerName() == null || entry.playerName().isBlank() ? entry.playerId().toString() : entry.playerName();
		String left = "#" + entry.rank() + " " + playerName;
		String balance = LunaCoreVelocity.services().moneyFormat().formatMinor(entry.balanceMinor(), VaultMoney.SCALE);
		String dots = Formatters.dotLeader(left, false, balance, false, TARGET_WIDTH_PX, ".");
		return "<color:" + LunaPalette.SKY_300 + ">" + left + "</color>"
			+ "<color:" + LunaPalette.NEUTRAL_500 + ">" + dots + "</color>"
			+ "<color:" + LunaPalette.GOLD_300 + ">" + balance + "</color>";
	}
}
