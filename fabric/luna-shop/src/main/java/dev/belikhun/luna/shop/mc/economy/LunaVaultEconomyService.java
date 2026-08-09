package dev.belikhun.luna.shop.mc.economy;

import dev.belikhun.luna.core.api.concurrent.FutureUtils;
import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
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
public final class LunaVaultEconomyService implements ShopEconomyService {
	private static final String ACTOR_NAME = "LunaShop";
	private static final String SOURCE = "lunashop";
	private static final long SERVER_THREAD_MUTATING_TIMEOUT_MILLIS = 450L;
	private static final long READ_GRACE_MILLIS = 250L;

	private final MinecraftServer server;
	private final LunaVaultApi vaultApi;
	private final long timeoutMillis;
	private final YamlConfigFile coreConfig;

	public LunaVaultEconomyService(MinecraftServer server, LunaVaultApi vaultApi, long timeoutMillis, YamlConfigFile coreConfig) {
		this.server = server;
		this.vaultApi = vaultApi;
		this.timeoutMillis = Math.max(1000L, timeoutMillis);
		this.coreConfig = coreConfig;
	}

	@Override
	public double balance(ServerPlayer player) {
		if (player == null) {
			return 0D;
		}

		long balanceMinor = await(vaultApi.balance(player.getUUID(), player.getName().getString()), 0L);
		return VaultMoney.toMajorDouble(balanceMinor);
	}

	@Override
	public boolean has(ServerPlayer player, double amount) {
		if (player == null || amount < 0D) {
			return false;
		}

		return await(vaultApi.has(player.getUUID(), player.getName().getString(), VaultMoney.fromDouble(amount)), false);
	}

	@Override
	public boolean withdraw(ServerPlayer player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		VaultOperationResult result = awaitMutating(vaultApi.withdraw(
			null,
			ACTOR_NAME,
			player.getUUID(),
			player.getName().getString(),
			VaultMoney.fromDouble(amount),
			SOURCE,
			"Mua vật phẩm từ LunaShop"
		));

		return result != null && result.success();
	}

	@Override
	public boolean deposit(ServerPlayer player, double amount) {
		if (player == null || amount <= 0D) {
			return false;
		}

		VaultOperationResult result = awaitMutating(vaultApi.deposit(
			null,
			ACTOR_NAME,
			player.getUUID(),
			player.getName().getString(),
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
		return FutureUtils.await(future, timeoutMillis + READ_GRACE_MILLIS, fallback, server.isSameThread());
	}

	private VaultOperationResult awaitMutating(CompletableFuture<VaultOperationResult> future) {
		if (future == null) {
			return null;
		}

		if (!server.isSameThread()) {
			return FutureUtils.await(future, timeoutMillis + READ_GRACE_MILLIS, null, false);
		}

		try {
			long timeout = Math.min(timeoutMillis + READ_GRACE_MILLIS, SERVER_THREAD_MUTATING_TIMEOUT_MILLIS);
			return future.get(Math.max(1L, timeout), TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			return null;
		}
	}
}
