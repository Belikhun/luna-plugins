package dev.belikhun.luna.legacy.shop;

import dev.belikhun.luna.legacy.concurrent.Futures;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.vault.LunaVaultApi;
import dev.belikhun.luna.legacy.vault.VaultMoney;
import dev.belikhun.luna.legacy.vault.VaultOperationResult;

import dev.belikhun.luna.legacy.logging.LunaLogger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The shop's wallet, talking to LunaVault.
 *
 * Every call here is made while a player is clicking a button, so it runs on the
 * server thread and cannot afford to wait on the proxy for long. Reads take the
 * gateway's cache; a write is given a much shorter budget than the transport's
 * own timeout, because holding the server thread for three seconds to move money
 * is worse than reporting that the trade did not go through.
 */
public final class LunaVaultEconomyService<P> implements ShopEconomyService<P> {
	private static final String ACTOR_NAME = "LunaShop";
	private static final String SOURCE = "lunashop";
	private static final long READ_GRACE_MILLIS = 250L;

	/** Past this, a trade is holding up ticks and should be visible in the log. */
	private static final long SLOW_CALL_WARN_MILLIS = 500L;

	/** Whether the calling thread is the one a tick runs on. */
	private final BooleanSupplier onServerThread;
	private final PlayerBridge<P> players;
	private final LunaVaultApi vaultApi;
	private final long timeoutMillis;
	private final YamlConfigFile coreConfig;
	private final LunaLogger logger;

	public LunaVaultEconomyService(
		BooleanSupplier onServerThread,
		PlayerBridge<P> players,
		LunaVaultApi vaultApi,
		long timeoutMillis,
		YamlConfigFile coreConfig,
		LunaLogger logger
	) {
		this.onServerThread = onServerThread;
		this.players = players;
		this.vaultApi = vaultApi;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		this.coreConfig = coreConfig;
		this.logger = logger.scope("Economy");
	}

	@Override
	public double balance(P player) {
		if (player == null) {
			return 0D;
		}

		long balanceMinor = await(vaultApi.balance(players.idOf(player), players.nameOf(player)), 0L);
		return VaultMoney.toMajorDouble(balanceMinor);
	}

	@Override
	public boolean has(P player, double amount) {
		if (player == null || amount < 0D) {
			return false;
		}

		return await(vaultApi.has(players.idOf(player), players.nameOf(player), VaultMoney.fromDouble(amount)), false);
	}

	@Override
	public boolean withdraw(P player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		final long minor = VaultMoney.fromDouble(amount);
		final UUID playerId = players.idOf(player);
		final String playerName = players.nameOf(player);

		VaultOperationResult result = awaitMutating(
			vaultApi.withdraw(null, ACTOR_NAME, playerId, playerName, minor, SOURCE, "Mua vật phẩm từ LunaShop"),
			() -> vaultApi.deposit(null, ACTOR_NAME, playerId, playerName, minor, SOURCE, "Hoàn tiền: giao dịch LunaShop không hoàn tất"),
			"trừ tiền"
		);

		return result != null && result.success();
	}

	@Override
	public boolean deposit(P player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		final long minor = VaultMoney.fromDouble(amount);
		final UUID playerId = players.idOf(player);
		final String playerName = players.nameOf(player);

		VaultOperationResult result = awaitMutating(
			vaultApi.deposit(null, ACTOR_NAME, playerId, playerName, minor, SOURCE, "Bán vật phẩm cho LunaShop"),
			() -> vaultApi.withdraw(null, ACTOR_NAME, playerId, playerName, minor, SOURCE, "Thu hồi: giao dịch LunaShop không hoàn tất"),
			"cộng tiền"
		);

		return result != null && result.success();
	}

	@Override
	public CompletableFuture<Double> balanceAsync(P player) {
		if (player == null) {
			return CompletableFuture.completedFuture(Double.valueOf(0D));
		}

		return vaultApi.balance(players.idOf(player), players.nameOf(player))
			.handle((balanceMinor, failure) -> {
				if (failure != null || balanceMinor == null) {
					logger.warn("LunaVault đọc số dư lỗi: " + failure);

					return Double.valueOf(0D);
				}

				return Double.valueOf(VaultMoney.toMajorDouble(balanceMinor.longValue()));
			});
	}

	@Override
	public CompletableFuture<Boolean> hasAsync(P player, double amount) {
		if (player == null || amount < 0D) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return vaultApi.has(players.idOf(player), players.nameOf(player), VaultMoney.fromDouble(amount))
			.handle((affordable, failure) -> {
				if (failure != null || affordable == null) {
					logger.warn("LunaVault kiểm tra số dư lỗi: " + failure);

					return Boolean.FALSE;
				}

				return affordable;
			});
	}

	@Override
	public CompletableFuture<Boolean> withdrawAsync(P player, double amount) {
		if (player == null || amount <= 0D) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return settle(vaultApi.withdraw(
			null,
			ACTOR_NAME,
			players.idOf(player),
			players.nameOf(player),
			VaultMoney.fromDouble(amount),
			SOURCE,
			"Mua vật phẩm từ LunaShop"
		), "trừ tiền");
	}

	@Override
	public CompletableFuture<Boolean> depositAsync(P player, double amount) {
		if (player == null || amount <= 0D) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return settle(vaultApi.deposit(
			null,
			ACTOR_NAME,
			players.idOf(player),
			players.nameOf(player),
			VaultMoney.fromDouble(amount),
			SOURCE,
			"Bán vật phẩm cho LunaShop"
		), "cộng tiền");
	}

	/**
	 * Turn a money move into a plain yes or no, waiting for the real answer.
	 *
	 * There is no timeout here and there must not be one. The gateway already
	 * fails a request it has given up on - it completes the future exceptionally
	 * on its own clock - so this cannot hang, and adding a second, shorter deadline
	 * would recreate exactly the abandoned-call problem the blocking path needs
	 * {@link #abandon} to repair.
	 */
	private CompletableFuture<Boolean> settle(CompletableFuture<VaultOperationResult> future, final String what) {
		if (future == null) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		return future.handle((result, failure) -> {
			if (failure != null) {
				logger.warn("LunaVault " + what + " lỗi: " + failure);

				return Boolean.FALSE;
			}

			return Boolean.valueOf(result != null && result.success());
		});
	}

	@Override
	public String format(double amount) {
		return Formatters.money(coreConfig, amount);
	}

	/**
	 * A read, timed.
	 *
	 * The timing is not decoration: a read and a write take the same road, so what a
	 * read costs is the only cheap measurement of what a trade will cost, and a
	 * balance lookup that quietly takes seconds is a stalled tick nobody reports.
	 */
	private <T> T await(CompletableFuture<T> future, T fallback) {
		long startedAt = System.currentTimeMillis();
		T result = Futures.await(future, timeoutMillis + READ_GRACE_MILLIS, fallback, onServerThread.getAsBoolean());
		long elapsed = System.currentTimeMillis() - startedAt;

		if (elapsed >= SLOW_CALL_WARN_MILLIS) {
			logger.warn("LunaVault đọc số dư mất " + elapsed + "ms.");
		}

		return result;
	}

	/**
	 * Wait for a money move, and never leave one half-done.
	 *
	 * @param compensation the opposite move, run only if the call we gave up on
	 *                     turns out to have gone through
	 * @param what         what to call this in the log, for a human reading it later
	 */
	private VaultOperationResult awaitMutating(
		CompletableFuture<VaultOperationResult> future,
		Supplier<CompletableFuture<VaultOperationResult>> compensation,
		String what
	) {
		if (future == null) {
			return null;
		}

		// the same budget a read gets, because a write travels the same road: a
		// shorter one only decides how often the player is told the opposite of what
		// happened, since the request is already in flight either way
		long startedAt = System.currentTimeMillis();

		try {
			VaultOperationResult result = future.get(Math.max(1L, timeoutMillis + READ_GRACE_MILLIS), TimeUnit.MILLISECONDS);
			long elapsed = System.currentTimeMillis() - startedAt;

			if (elapsed >= SLOW_CALL_WARN_MILLIS) {
				logger.warn("LunaVault " + what + " mất " + elapsed + "ms, giữ luồng máy chủ lâu hơn dự kiến.");
			}

			return result;
		} catch (TimeoutException timedOut) {
			abandon(future, compensation, what, startedAt);

			return null;
		} catch (Exception failure) {
			logger.warn("LunaVault " + what + " lỗi: " + failure);

			return null;
		}
	}

	/**
	 * Follow a call we stopped waiting for, and undo it if it lands.
	 *
	 * **Giving up on the future cancels nothing.** The proxy owns the money and may
	 * commit the transfer a moment after the shop has already told the player it
	 * failed, which leaves them charged for goods they never received - and nothing
	 * written afterwards can tell that apart from a trade that genuinely did not
	 * happen. So the real outcome is followed to the end, and a transfer that did go
	 * through is reversed by its opposite. Only then is "thất bại" true.
	 */
	private void abandon(
		CompletableFuture<VaultOperationResult> future,
		final Supplier<CompletableFuture<VaultOperationResult>> compensation,
		final String what,
		final long startedAt
	) {
		logger.warn("LunaVault " + what + " chưa trả lời sau " + (System.currentTimeMillis() - startedAt)
			+ "ms. Giao dịch bị báo hỏng; đang chờ kết quả thật.");

		future.whenComplete((result, failure) -> {
			long elapsed = System.currentTimeMillis() - startedAt;

			if (failure != null || result == null || !result.success()) {
				logger.warn("LunaVault " + what + " không thành công sau " + elapsed + "ms; không có gì để hoàn tác.");

				return;
			}

			logger.warn("LunaVault " + what + " thành công sau " + elapsed
				+ "ms, muộn hơn lúc giao dịch bị báo hỏng. Đang hoàn tác.");
			compensate(compensation, what);
		});
	}

	private void compensate(Supplier<CompletableFuture<VaultOperationResult>> compensation, final String what) {
		CompletableFuture<VaultOperationResult> reversal = compensation == null ? null : compensation.get();

		if (reversal == null) {
			logger.error("Không hoàn tác được " + what + ": thiếu thao tác ngược. Cần kiểm tra số dư thủ công.");

			return;
		}

		reversal.whenComplete((result, failure) -> {
			if (failure != null || result == null || !result.success()) {
				logger.error("Hoàn tác " + what + " THẤT BẠI. Số dư của người chơi cần được kiểm tra thủ công.");

				return;
			}

			logger.audit("Đã hoàn tác " + what + " sau khi giao dịch bị báo hỏng.");
		});
	}
}
