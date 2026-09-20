package dev.belikhun.luna.vault.backend.command;

import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.client.VaultClient;
import dev.belikhun.luna.vault.backend.service.PaperVaultGateway;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drive the economy from a backend console, with nobody logged in.
 *
 * {@code deposit} and {@code withdraw} go through the Vault {@link Economy}
 * provider, exactly as a third-party plugin would, so they exercise the
 * synchronous main-thread path those plugins take. {@code stress} and
 * {@code overdraw} fire many operations at once through the async api and
 * then check the one invariant that matters: the balance afterwards is what
 * the successes add up to, and never below zero.
 */
public final class VaultAdminCommand implements BasicCommand {
	private static final String PERMISSION = "lunavault.admin";
	private static final int MAX_STRESS_ROUNDS = 500;
	private static final long RESULT_WAIT_SECONDS = 60L;
	private static final String SOURCE = "lunavault-admin";

	private final JavaPlugin plugin;
	private final PaperVaultGateway gateway;
	private final Economy economy;

	public VaultAdminCommand(JavaPlugin plugin, PaperVaultGateway gateway, Economy economy) {
		this.plugin = plugin;
		this.gateway = gateway;
		this.economy = economy;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();

		if (args.length == 0) {
			sendUsage(sender);
			return;
		}

		String action = args[0].toLowerCase(Locale.ROOT);

		switch (action) {
			case "status" -> handleStatus(sender);
			case "balance", "bal" -> handleBalance(sender, args);
			case "deposit", "withdraw" -> handleEconomy(sender, action, args);
			case "transfer" -> handleTransfer(sender, args);
			case "history" -> handleHistory(sender, args);
			case "stress" -> handleStress(sender, args);
			case "overdraw" -> handleOverdraw(sender, args);
			default -> sendUsage(sender);
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length <= 1) {
			String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
			List<String> actions = List.of("status", "balance", "deposit", "withdraw", "transfer", "history", "stress", "overdraw");
			return actions.stream().filter(value -> value.startsWith(partial)).toList();
		}

		if (args.length == 2 || ("transfer".equalsIgnoreCase(args[0]) && args.length == 3)) {
			String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
			List<String> names = new ArrayList<>();

			for (Player online : Bukkit.getOnlinePlayers()) {
				if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
					names.add(online.getName());
				}
			}

			return names;
		}

		return List.of();
	}

	@Override
	public String permission() {
		return PERMISSION;
	}

	// ---------------------------------------------------------------- actions

	private void handleStatus(CommandSender sender) {
		VaultClient.Status status = gateway.status();

		sender.sendRichMessage("<gray>LunaVault backend</gray>");
		sender.sendRichMessage("<gray>  cache: <white>" + status.cachedPlayers() + "</white> người chơi · đang chờ: <white>" + status.pendingRequests()
			+ "</white> yêu cầu, <white>" + status.inFlightSnapshots() + "</white> snapshot</gray>");
		sender.sendRichMessage("<gray>  hết hạn chờ: <white>" + status.timedOut() + "</white> · đối soát sau hết hạn: <white>" + status.settledAfterTimeout()
			+ "</white> · ngân sách mỗi yêu cầu: <white>" + status.requestTimeoutMillis() + "ms</white></gray>");
	}

	private void handleBalance(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer target = resolve(args[1]);

		if (target == null) {
			sender.sendRichMessage("<red>❌ Không biết người chơi " + args[1] + ".</red>");
			return;
		}

		VaultPlayerSnapshot cached = gateway.cachedSnapshot(target.getUniqueId());
		String cachedText = cached == null ? "chưa có" : money(cached.balanceMinor()) + " (hạng " + cached.rank() + ", v" + cached.version() + ")";
		sender.sendRichMessage("<gray>cache: <white>" + cachedText + "</white></gray>");

		long startedAt = System.nanoTime();
		gateway.snapshot(target.getUniqueId(), target.getName()).whenComplete((snapshot, throwable) -> {
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

			if (throwable != null || snapshot == null) {
				sender.sendRichMessage("<red>❌ Không lấy được snapshot từ proxy.</red>");
				return;
			}

			sender.sendRichMessage("<green>✔ proxy: <white>" + money(snapshot.balanceMinor()) + "</white> (hạng " + snapshot.rank() + ", v" + snapshot.version() + ") sau " + elapsedMillis + "ms</green>");
		});
	}

	/** Through the Vault provider, on the main thread, the way every other plugin calls it. */
	private void handleEconomy(CommandSender sender, String action, String[] args) {
		if (args.length < 3) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer target = resolve(args[1]);
		OptionalLong amountMinor = VaultMoney.parseUserInput(args[2]);

		if (target == null) {
			sender.sendRichMessage("<red>❌ Không biết người chơi " + args[1] + ".</red>");
			return;
		}

		if (amountMinor.isEmpty() || amountMinor.getAsLong() <= 0L) {
			sender.sendRichMessage("<red>❌ Số tiền không hợp lệ. Hãy dùng định dạng 00.00.</red>");
			return;
		}

		double amount = VaultMoney.toMajorDouble(amountMinor.getAsLong());
		long startedAt = System.nanoTime();
		EconomyResponse response = "deposit".equals(action)
			? economy.depositPlayer(target, amount)
			: economy.withdrawPlayer(target, amount);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

		if (!response.transactionSuccess()) {
			sender.sendRichMessage("<red>❌ Vault " + action + " thất bại sau " + elapsedMillis + "ms: " + response.errorMessage + "</red>");
			return;
		}

		sender.sendRichMessage("<green>✔ Vault " + action + " " + economy.format(amount) + " cho <white>" + target.getName() + "</white> sau " + elapsedMillis
			+ "ms; số dư: <white>" + economy.format(response.balance) + "</white></green>");
	}

	private void handleTransfer(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer from = resolve(args[1]);
		OfflinePlayer to = resolve(args[2]);
		OptionalLong amountMinor = VaultMoney.parseUserInput(args[3]);

		if (from == null || to == null) {
			sender.sendRichMessage("<red>❌ Không biết một trong hai người chơi.</red>");
			return;
		}

		if (amountMinor.isEmpty() || amountMinor.getAsLong() <= 0L) {
			sender.sendRichMessage("<red>❌ Số tiền không hợp lệ.</red>");
			return;
		}

		long startedAt = System.nanoTime();
		gateway.transfer(from.getUniqueId(), from.getName(), to.getUniqueId(), to.getName(), amountMinor.getAsLong(), SOURCE, "lunavault transfer từ console")
			.thenAccept(result -> report(sender, "transfer", result, startedAt));
	}

	private void handleHistory(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer target = resolve(args[1]);

		if (target == null) {
			sender.sendRichMessage("<red>❌ Không biết người chơi " + args[1] + ".</red>");
			return;
		}

		int page = 0;

		if (args.length >= 3) {
			try {
				page = Math.max(0, Integer.parseInt(args[2]) - 1);
			} catch (NumberFormatException notANumber) {
				sender.sendRichMessage("<red>❌ Trang không hợp lệ.</red>");
				return;
			}
		}

		gateway.history(target.getUniqueId(), page, 10).whenComplete((history, throwable) -> {
			if (throwable != null || history == null) {
				sender.sendRichMessage("<red>❌ Không tải được lịch sử: " + (throwable == null ? "rỗng" : throwable.getMessage()) + "</red>");
				return;
			}

			sender.sendRichMessage("<gray>Lịch sử của <white>" + target.getName() + "</white>, trang " + (history.page() + 1) + "/" + (history.maxPage() + 1)
				+ " (" + history.totalCount() + " giao dịch)</gray>");

			for (VaultTransactionRecord record : history.entries()) {
				Long after = record.balanceAfterFor(target.getUniqueId());
				sender.sendRichMessage("<gray>  " + record.kind().name() + " <white>" + name(record.senderName(), record.senderId()) + "</white> → <white>"
					+ name(record.receiverName(), record.receiverId()) + "</white> " + money(record.amountMinor())
					+ (after == null ? "" : " → còn " + money(after)) + " <dark_gray>" + record.source() + "</dark_gray></gray>");
			}
		});
	}

	/**
	 * Many deposits and as many withdrawals at once.
	 *
	 * Every pair nets to zero, so whatever order the proxy applies them in the
	 * balance must come back to where it started; anything else is a lost update.
	 */
	private void handleStress(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer target = resolve(args[1]);
		int rounds = parseInt(args[2], 0);
		OptionalLong amountMinor = VaultMoney.parseUserInput(args[3]);

		if (target == null || rounds <= 0 || rounds > MAX_STRESS_ROUNDS || amountMinor.isEmpty() || amountMinor.getAsLong() <= 0L) {
			sender.sendRichMessage("<red>❌ Cần người chơi, số vòng (1-" + MAX_STRESS_ROUNDS + ") và số tiền hợp lệ.</red>");
			return;
		}

		UUID playerId = target.getUniqueId();
		String playerName = target.getName();
		long amount = amountMinor.getAsLong();

		gateway.snapshot(playerId, playerName).thenAccept(before -> {
			sender.sendRichMessage("<gray>Bắt đầu " + rounds + " cặp nạp/rút " + money(amount) + " song song; số dư ban đầu " + money(before.balanceMinor()) + "</gray>");

			// deposit first so a withdrawal never fails for want of funds; the
			// ordering the proxy applies them in is still up to the network
			List<CompletableFuture<VaultOperationResult>> operations = new ArrayList<>();
			long startedAt = System.nanoTime();

			for (int round = 0; round < rounds; round++) {
				operations.add(gateway.deposit(null, "stress", playerId, playerName, amount, SOURCE, "stress nạp #" + round));
			}

			for (int round = 0; round < rounds; round++) {
				operations.add(gateway.withdraw(null, "stress", playerId, playerName, amount, SOURCE, "stress rút #" + round));
			}

			finish(sender, operations, startedAt, () -> gateway.snapshot(playerId, playerName).thenAccept(after -> {
				long expected = before.balanceMinor();
				boolean ok = after.balanceMinor() == expected;
				sender.sendRichMessage((ok ? "<green>✔" : "<red>❌") + " Số dư sau: <white>" + money(after.balanceMinor()) + "</white>, mong đợi " + money(expected)
					+ (ok ? " · khớp" : " · LỆCH " + money(after.balanceMinor() - expected)) + (ok ? "</green>" : "</red>"));
			}), null);
		});
	}

	/**
	 * Race many withdrawals against one balance.
	 *
	 * With balance b and n withdrawals of a, exactly min(n, b / a) may succeed
	 * and the balance must end at b minus that many a; a single extra success is
	 * an overdraft the ledger is supposed to make impossible.
	 */
	private void handleOverdraw(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sendUsage(sender);
			return;
		}

		OfflinePlayer target = resolve(args[1]);
		int attempts = parseInt(args[2], 0);
		OptionalLong amountMinor = VaultMoney.parseUserInput(args[3]);

		if (target == null || attempts <= 0 || attempts > MAX_STRESS_ROUNDS || amountMinor.isEmpty() || amountMinor.getAsLong() <= 0L) {
			sender.sendRichMessage("<red>❌ Cần người chơi, số lần rút (1-" + MAX_STRESS_ROUNDS + ") và số tiền hợp lệ.</red>");
			return;
		}

		UUID playerId = target.getUniqueId();
		String playerName = target.getName();
		long amount = amountMinor.getAsLong();

		gateway.snapshot(playerId, playerName).thenAccept(before -> {
			long affordable = Math.min(attempts, before.balanceMinor() / amount);
			sender.sendRichMessage("<gray>Rút " + attempts + " × " + money(amount) + " cùng lúc từ " + money(before.balanceMinor()) + "; tối đa " + affordable + " lần được phép thành công</gray>");

			List<CompletableFuture<VaultOperationResult>> operations = new ArrayList<>();
			long startedAt = System.nanoTime();

			for (int attempt = 0; attempt < attempts; attempt++) {
				operations.add(gateway.withdraw(null, "overdraw", playerId, playerName, amount, SOURCE, "overdraw #" + attempt));
			}

			finish(sender, operations, startedAt, () -> gateway.snapshot(playerId, playerName).thenAccept(after -> {
				long succeeded = operations.stream().filter(future -> future.getNow(null) != null && future.getNow(null).success()).count();
				long expected = before.balanceMinor() - succeeded * amount;
				boolean ok = succeeded == affordable && after.balanceMinor() == expected && after.balanceMinor() >= 0L;
				sender.sendRichMessage((ok ? "<green>✔" : "<red>❌") + " " + succeeded + " lần thành công (cho phép " + affordable + "), số dư sau <white>" + money(after.balanceMinor())
					+ "</white>, mong đợi " + money(expected) + (ok ? "</green>" : " · SAI</red>"));
			}), null);
		});
	}

	// ---------------------------------------------------------------- helpers

	private void finish(CommandSender sender, List<CompletableFuture<VaultOperationResult>> operations, long startedAt, Runnable verify, Object unused) {
		CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new))
			.orTimeout(RESULT_WAIT_SECONDS, TimeUnit.SECONDS)
			.whenComplete((ignored, throwable) -> {
				long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
				Map<VaultFailureReason, AtomicLong> failures = new EnumMap<>(VaultFailureReason.class);
				long successes = 0L;
				long unfinished = 0L;

				for (CompletableFuture<VaultOperationResult> operation : operations) {
					VaultOperationResult result = operation.getNow(null);

					if (result == null) {
						unfinished++;
						continue;
					}

					if (result.success()) {
						successes++;
						continue;
					}

					failures.computeIfAbsent(result.failureReason(), key -> new AtomicLong()).incrementAndGet();
				}

				StringBuilder summary = new StringBuilder("<gray>" + operations.size() + " yêu cầu trong " + elapsedMillis + "ms: <green>" + successes + " thành công</green>");

				for (Map.Entry<VaultFailureReason, AtomicLong> entry : failures.entrySet()) {
					summary.append(", <red>").append(entry.getValue().get()).append(' ').append(entry.getKey().name()).append("</red>");
				}

				if (unfinished > 0L) {
					summary.append(", <red>").append(unfinished).append(" chưa xong</red>");
				}

				sender.sendRichMessage(summary.append("</gray>").toString());

				// the settlement lookups after a timeout need a moment before the
				// balance is worth reading
				plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, verify, 40L);
			});
	}

	private void report(CommandSender sender, String action, VaultOperationResult result, long startedAt) {
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

		if (!result.success()) {
			sender.sendRichMessage("<red>❌ " + action + " thất bại sau " + elapsedMillis + "ms: " + result.failureReason() + " · " + result.message() + "</red>");
			return;
		}

		sender.sendRichMessage("<green>✔ " + action + " xong sau " + elapsedMillis + "ms; số dư người gửi: <white>" + money(result.balanceMinor()) + "</white></green>");
	}

	private OfflinePlayer resolve(String reference) {
		if (reference == null || reference.isBlank()) {
			return null;
		}

		try {
			return Bukkit.getOfflinePlayer(UUID.fromString(reference.trim()));
		} catch (IllegalArgumentException notAUuid) {
			// a name, then
		}

		Player online = Bukkit.getPlayerExact(reference);

		if (online != null) {
			return online;
		}

		OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(reference);

		if (cached != null) {
			return cached;
		}

		OfflinePlayer looked = Bukkit.getOfflinePlayer(reference);
		return looked.hasPlayedBefore() || looked.getName() != null ? looked : null;
	}

	private int parseInt(String raw, int fallback) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private String money(long minor) {
		return Formatters.stripFormats(economy.format(VaultMoney.toMajorDouble(minor)));
	}

	private String name(String playerName, UUID playerId) {
		if (playerName != null && !playerName.isBlank()) {
			return playerName;
		}

		return playerId == null ? "HỆ THỐNG" : playerId.toString();
	}

	private void sendUsage(CommandSender sender) {
		sender.sendRichMessage("<gray>/lunavault status</gray>");
		sender.sendRichMessage("<gray>/lunavault balance <người_chơi></gray>");
		sender.sendRichMessage("<gray>/lunavault deposit|withdraw <người_chơi> <số_tiền></gray>");
		sender.sendRichMessage("<gray>/lunavault transfer <từ> <đến> <số_tiền></gray>");
		sender.sendRichMessage("<gray>/lunavault history <người_chơi> [trang]</gray>");
		sender.sendRichMessage("<gray>/lunavault stress <người_chơi> <số_vòng> <số_tiền></gray>");
		sender.sendRichMessage("<gray>/lunavault overdraw <người_chơi> <số_lần> <số_tiền></gray>");
	}
}
