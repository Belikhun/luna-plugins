package dev.belikhun.luna.vault.backend.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.client.VaultClient;
import dev.belikhun.luna.vault.backend.mc.service.VaultGateway;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code /lunavault} on a mod loader: drive the economy from the console.
 *
 * The Paper build has the same command; this is the Brigadier shape of it,
 * shared by the fabric, neoforge and forge bootstraps. A target is an online
 * player's name or any UUID, since a mod server has no offline-player cache
 * to turn a name into an id.
 */
public final class VaultAdminCommands {
	private static final int MAX_STRESS_ROUNDS = 500;
	private static final long RESULT_WAIT_SECONDS = 60L;
	private static final String SOURCE = "lunavault-admin";
	private static final String NOT_READY = "<red>❌ LunaVaultBackend chưa sẵn sàng.</red>";

	private final Supplier<VaultGateway> gateway;
	private final LongFunction<String> money;

	private VaultAdminCommands(Supplier<VaultGateway> gateway, LongFunction<String> money) {
		this.gateway = gateway;
		this.money = money;
	}

	/**
	 * @param gateway the gateway, read at execution time because commands register before the server starts
	 * @param money formats a minor amount the way the core's config says to
	 * @param mayUse who may run it; the console always may
	 */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<VaultGateway> gateway, LongFunction<String> money, Predicate<CommandSourceStack> mayUse) {
		VaultAdminCommands commands = new VaultAdminCommands(gateway, money);

		dispatcher.register(Commands.literal("lunavault")
			.requires(mayUse)
			.then(Commands.literal("status").executes(context -> commands.status(context.getSource())))
			.then(Commands.literal("balance")
				.then(Commands.argument("player", StringArgumentType.word())
					.executes(context -> commands.balance(context.getSource(), StringArgumentType.getString(context, "player")))))
			.then(Commands.literal("deposit")
				.then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("amount", StringArgumentType.word())
						.executes(context -> commands.mutate(context, true)))))
			.then(Commands.literal("withdraw")
				.then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("amount", StringArgumentType.word())
						.executes(context -> commands.mutate(context, false)))))
			.then(Commands.literal("stress")
				.then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("rounds", IntegerArgumentType.integer(1, MAX_STRESS_ROUNDS))
						.then(Commands.argument("amount", StringArgumentType.word())
							.executes(commands::stress)))))
			.then(Commands.literal("overdraw")
				.then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("attempts", IntegerArgumentType.integer(1, MAX_STRESS_ROUNDS))
						.then(Commands.argument("amount", StringArgumentType.word())
							.executes(commands::overdraw))))));
	}

	private int status(CommandSourceStack source) {
		VaultGateway current = gateway.get();

		if (current == null) {
			say(source, NOT_READY);
			return 0;
		}

		VaultClient.Status status = current.status();
		say(source, "<gray>LunaVault backend</gray>");
		say(source, "<gray>  cache: <white>" + status.cachedPlayers() + "</white> người chơi · đang chờ: <white>" + status.pendingRequests()
			+ "</white> yêu cầu, <white>" + status.inFlightSnapshots() + "</white> snapshot</gray>");
		say(source, "<gray>  hết hạn chờ: <white>" + status.timedOut() + "</white> · đối soát sau hết hạn: <white>" + status.settledAfterTimeout()
			+ "</white> · ngân sách mỗi yêu cầu: <white>" + status.requestTimeoutMillis() + "ms</white></gray>");
		return 1;
	}

	private int balance(CommandSourceStack source, String reference) {
		VaultGateway current = gateway.get();
		Target target = resolve(source, reference);

		if (current == null || target == null) {
			say(source, current == null ? NOT_READY : "<red>❌ Không biết người chơi " + reference + ".</red>");
			return 0;
		}

		VaultPlayerSnapshot cached = current.cachedSnapshot(target.id());
		say(source, "<gray>cache: <white>" + (cached == null ? "chưa có" : money.apply(cached.balanceMinor()) + " (hạng " + cached.rank() + ", v" + cached.version() + ")") + "</white></gray>");

		long startedAt = System.nanoTime();
		current.snapshot(target.id(), target.name()).whenComplete((snapshot, throwable) -> {
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

			if (throwable != null || snapshot == null) {
				say(source, "<red>❌ Không lấy được snapshot từ proxy.</red>");
				return;
			}

			say(source, "<green>✔ proxy: <white>" + money.apply(snapshot.balanceMinor()) + "</white> (hạng " + snapshot.rank() + ", v" + snapshot.version() + ") sau " + elapsedMillis + "ms</green>");
		});

		return 1;
	}

	private int mutate(CommandContext<CommandSourceStack> context, boolean deposit) {
		CommandSourceStack source = context.getSource();
		VaultGateway current = gateway.get();
		Target target = resolve(source, StringArgumentType.getString(context, "player"));
		OptionalLong amount = VaultMoney.parseUserInput(StringArgumentType.getString(context, "amount"));

		if (current == null || target == null || amount.isEmpty() || amount.getAsLong() <= 0L) {
			say(source, current == null ? NOT_READY : "<red>❌ Cần người chơi và số tiền hợp lệ (00.00).</red>");
			return 0;
		}

		long startedAt = System.nanoTime();
		CompletableFuture<VaultOperationResult> operation = deposit
			? current.deposit(null, "console", target.id(), target.name(), amount.getAsLong(), SOURCE, "lunavault deposit từ console")
			: current.withdraw(null, "console", target.id(), target.name(), amount.getAsLong(), SOURCE, "lunavault withdraw từ console");

		operation.thenAccept(result -> {
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

			if (!result.success()) {
				say(source, "<red>❌ Thất bại sau " + elapsedMillis + "ms: " + result.failureReason() + " · " + result.message() + "</red>");
				return;
			}

			say(source, "<green>✔ Xong sau " + elapsedMillis + "ms; số dư: <white>" + money.apply(result.balanceMinor()) + "</white></green>");
		});

		return 1;
	}

	private int stress(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VaultGateway current = gateway.get();
		Target target = resolve(source, StringArgumentType.getString(context, "player"));
		int rounds = IntegerArgumentType.getInteger(context, "rounds");
		OptionalLong amount = VaultMoney.parseUserInput(StringArgumentType.getString(context, "amount"));

		if (current == null || target == null || amount.isEmpty() || amount.getAsLong() <= 0L) {
			say(source, current == null ? NOT_READY : "<red>❌ Cần người chơi và số tiền hợp lệ.</red>");
			return 0;
		}

		long each = amount.getAsLong();

		current.snapshot(target.id(), target.name()).thenAccept(before -> {
			say(source, "<gray>Bắt đầu " + rounds + " cặp nạp/rút " + money.apply(each) + " song song; số dư ban đầu " + money.apply(before.balanceMinor()) + "</gray>");

			List<CompletableFuture<VaultOperationResult>> operations = new ArrayList<>();
			long startedAt = System.nanoTime();

			for (int round = 0; round < rounds; round++) {
				operations.add(current.deposit(null, "stress", target.id(), target.name(), each, SOURCE, "stress nạp #" + round));
			}

			for (int round = 0; round < rounds; round++) {
				operations.add(current.withdraw(null, "stress", target.id(), target.name(), each, SOURCE, "stress rút #" + round));
			}

			finish(source, operations, startedAt, () -> current.snapshot(target.id(), target.name()).thenAccept(after -> {
				boolean ok = after.balanceMinor() == before.balanceMinor();
				say(source, (ok ? "<green>✔" : "<red>❌") + " Số dư sau: <white>" + money.apply(after.balanceMinor()) + "</white>, mong đợi " + money.apply(before.balanceMinor())
					+ (ok ? " · khớp</green>" : " · LỆCH</red>"));
			}));
		});

		return 1;
	}

	private int overdraw(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		VaultGateway current = gateway.get();
		Target target = resolve(source, StringArgumentType.getString(context, "player"));
		int attempts = IntegerArgumentType.getInteger(context, "attempts");
		OptionalLong amount = VaultMoney.parseUserInput(StringArgumentType.getString(context, "amount"));

		if (current == null || target == null || amount.isEmpty() || amount.getAsLong() <= 0L) {
			say(source, current == null ? NOT_READY : "<red>❌ Cần người chơi và số tiền hợp lệ.</red>");
			return 0;
		}

		long each = amount.getAsLong();

		current.snapshot(target.id(), target.name()).thenAccept(before -> {
			long affordable = Math.min(attempts, before.balanceMinor() / each);
			say(source, "<gray>Rút " + attempts + " × " + money.apply(each) + " cùng lúc từ " + money.apply(before.balanceMinor()) + "; tối đa " + affordable + " lần được phép thành công</gray>");

			List<CompletableFuture<VaultOperationResult>> operations = new ArrayList<>();
			long startedAt = System.nanoTime();

			for (int attempt = 0; attempt < attempts; attempt++) {
				operations.add(current.withdraw(null, "overdraw", target.id(), target.name(), each, SOURCE, "overdraw #" + attempt));
			}

			finish(source, operations, startedAt, () -> current.snapshot(target.id(), target.name()).thenAccept(after -> {
				long succeeded = operations.stream().filter(future -> future.getNow(null) != null && future.getNow(null).success()).count();
				long expected = before.balanceMinor() - succeeded * each;
				boolean ok = succeeded == affordable && after.balanceMinor() == expected && after.balanceMinor() >= 0L;
				say(source, (ok ? "<green>✔" : "<red>❌") + " " + succeeded + " lần thành công (cho phép " + affordable + "), số dư sau <white>" + money.apply(after.balanceMinor())
					+ "</white>, mong đợi " + money.apply(expected) + (ok ? "</green>" : " · SAI</red>"));
			}));
		});

		return 1;
	}

	private void finish(CommandSourceStack source, List<CompletableFuture<VaultOperationResult>> operations, long startedAt, Runnable verify) {
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

				say(source, summary.append("</gray>").toString());

				// settlement lookups after a timeout need a moment before the balance is worth reading
				CompletableFuture.delayedExecutor(2L, TimeUnit.SECONDS).execute(verify);
			});
	}

	private Target resolve(CommandSourceStack source, String reference) {
		if (reference == null || reference.isBlank()) {
			return null;
		}

		try {
			UUID id = UUID.fromString(reference.trim());
			ServerPlayer online = source.getServer().getPlayerList().getPlayer(id);
			return new Target(id, online == null ? "" : online.getName().getString());
		} catch (IllegalArgumentException notAUuid) {
			// a name, then
		}

		ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(reference);

		if (online == null) {
			return null;
		}

		return new Target(online.getUUID(), online.getName().getString());
	}

	private static void say(CommandSourceStack source, String miniMessage) {
		source.sendSystemMessage(LunaTextComponents.mini(miniMessage));
	}

	private record Target(UUID id, String name) {
	}
}
