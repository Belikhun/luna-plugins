package dev.belikhun.luna.vault.backend.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

public final class PaperVaultGateway implements LunaVaultApi {
	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PluginMessageBus<Player, Player> bus;
	private final long requestTimeoutMillis;
	private final Map<UUID, CompletableFuture<VaultRpcResponse>> pendingRequests;

	public PaperVaultGateway(JavaPlugin plugin, LunaLogger logger, PluginMessageBus<Player, Player> bus, long requestTimeoutMillis) {
		this.plugin = plugin;
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.pendingRequests = new ConcurrentHashMap<>();
	}

	public void registerChannels() {
		bus.registerOutgoing(VaultChannels.RPC);
		bus.registerIncoming(VaultChannels.RPC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String kind = reader.readUtf();
			if (!"response".equals(kind)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			VaultRpcResponse response = VaultRpcResponse.readFrom(reader);
			CompletableFuture<VaultRpcResponse> future = pendingRequests.remove(response.correlationId());
			if (future != null) {
				future.complete(response);
			}
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	public void close() {
		bus.unregisterOutgoing(VaultChannels.RPC);
		bus.unregisterIncoming(VaultChannels.RPC);
		pendingRequests.values().forEach(future -> future.completeExceptionally(new IllegalStateException("LunaVaultBackend đang tắt.")));
		pendingRequests.clear();
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		return send(selectCarrier(playerId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.BALANCE,
			null,
			null,
			playerId,
			playerName,
			null,
			null,
			0L,
			"lunavaultbackend",
			null,
			0,
			1
		)).thenApply(response -> response.result().balanceMinor());
	}

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return send(selectCarrier(actorId, playerId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.DEPOSIT,
			actorId,
			actorName,
			playerId,
			playerName,
			null,
			null,
			amountMinor,
			source,
			details,
			0,
			1
		)).thenApply(VaultRpcResponse::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return send(selectCarrier(actorId, playerId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.WITHDRAW,
			actorId,
			actorName,
			playerId,
			playerName,
			null,
			null,
			amountMinor,
			source,
			details,
			0,
			1
		)).thenApply(VaultRpcResponse::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		return send(selectCarrier(senderId, receiverId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.TRANSFER,
			null,
			null,
			senderId,
			senderName,
			receiverId,
			receiverName,
			amountMinor,
			source,
			details,
			0,
			1
		)).thenApply(VaultRpcResponse::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		return send(selectCarrier(actorId, playerId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.SET_BALANCE,
			actorId,
			actorName,
			playerId,
			playerName,
			null,
			null,
			newBalanceMinor,
			source,
			details,
			0,
			1
		)).thenApply(VaultRpcResponse::result);
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		return send(selectCarrier(playerId), new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.HISTORY,
			null,
			null,
			playerId,
			playerId == null ? null : playerId.toString(),
			null,
			null,
			0L,
			"transactions",
			null,
			page,
			pageSize
		)).thenApply(VaultRpcResponse::page);
	}

	private CompletableFuture<VaultRpcResponse> send(Player carrier, VaultRpcRequest request) {
		if (carrier == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("Không có người chơi online để chuyển tiếp yêu cầu lên Velocity."));
		}

		CompletableFuture<VaultRpcResponse> future = new CompletableFuture<>();
		pendingRequests.put(request.correlationId(), future);
		boolean sent = bus.send(carrier, VaultChannels.RPC, writer -> request.writeTo(writer));
		if (!sent) {
			pendingRequests.remove(request.correlationId());
			return CompletableFuture.failedFuture(new IllegalStateException("Không thể gửi yêu cầu LunaVault lên Velocity."));
		}

		long timeoutTicks = Math.max(1L, (requestTimeoutMillis + 49L) / 50L);
		plugin.getServer().getScheduler().runTaskLater(plugin, task -> {
			CompletableFuture<VaultRpcResponse> pending = pendingRequests.remove(request.correlationId());
			if (pending != null && !pending.isDone()) {
				pending.completeExceptionally(new TimeoutException("Yêu cầu LunaVault bị timeout."));
			}
		}, timeoutTicks);
		return future;
	}

	private Player selectCarrier(UUID... preferredPlayers) {
		if (preferredPlayers != null) {
			for (UUID preferred : preferredPlayers) {
				if (preferred == null) {
					continue;
				}
				Player online = Bukkit.getPlayer(preferred);
				if (online != null && online.isOnline()) {
					return online;
				}
			}
		}

		return plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
	}
}
