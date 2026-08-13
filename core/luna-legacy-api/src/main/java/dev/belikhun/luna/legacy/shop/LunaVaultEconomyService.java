package dev.belikhun.luna.legacy.shop;

import dev.belikhun.luna.legacy.concurrent.Futures;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.string.Formatters;
import dev.belikhun.luna.legacy.vault.LunaVaultApi;
import dev.belikhun.luna.legacy.vault.VaultMoney;
import dev.belikhun.luna.legacy.vault.VaultOperationResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

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
	private static final long SERVER_THREAD_MUTATING_TIMEOUT_MILLIS = 450L;
	private static final long READ_GRACE_MILLIS = 250L;

	/** Whether the calling thread is the one a tick runs on. */
	private final BooleanSupplier onServerThread;
	private final PlayerBridge<P> players;
	private final LunaVaultApi vaultApi;
	private final long timeoutMillis;
	private final YamlConfigFile coreConfig;

	public LunaVaultEconomyService(
		BooleanSupplier onServerThread,
		PlayerBridge<P> players,
		LunaVaultApi vaultApi,
		long timeoutMillis,
		YamlConfigFile coreConfig
	) {
		this.onServerThread = onServerThread;
		this.players = players;
		this.vaultApi = vaultApi;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		this.coreConfig = coreConfig;
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

		VaultOperationResult result = awaitMutating(vaultApi.withdraw(
			null,
			ACTOR_NAME,
			players.idOf(player),
			players.nameOf(player),
			VaultMoney.fromDouble(amount),
			SOURCE,
			"Mua vật phẩm từ LunaShop"
		));

		return result != null && result.success();
	}

	@Override
	public boolean deposit(P player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		VaultOperationResult result = awaitMutating(vaultApi.deposit(
			null,
			ACTOR_NAME,
			players.idOf(player),
			players.nameOf(player),
			VaultMoney.fromDouble(amount),
			SOURCE,
			"Bán vật phẩm cho LunaShop"
		));

		return result != null && result.success();
	}

	@Override
	public String format(double amount) {
		return Formatters.money(coreConfig, amount);
	}

	private <T> T await(CompletableFuture<T> future, T fallback) {
		return Futures.await(future, timeoutMillis + READ_GRACE_MILLIS, fallback, onServerThread.getAsBoolean());
	}

	private VaultOperationResult awaitMutating(CompletableFuture<VaultOperationResult> future) {
		if (future == null) {
			return null;
		}

		if (!onServerThread.getAsBoolean()) {
			return Futures.await(future, timeoutMillis + READ_GRACE_MILLIS, null, false);
		}

		try {
			long timeout = Math.min(timeoutMillis + READ_GRACE_MILLIS, SERVER_THREAD_MUTATING_TIMEOUT_MILLIS);
			return future.get(Math.max(1L, timeout), TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			return null;
		}
	}
}
