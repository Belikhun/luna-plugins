package dev.belikhun.luna.vault.backend.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class PaperVaultGateway implements LunaVaultApi, Listener {
	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PluginMessageBus<Player, Player> bus;
	private final long requestTimeoutMillis;
	private final Map<UUID, CompletableFuture<VaultRpcResponse>> pendingRequests;
	private final Map<UUID, CompletableFuture<VaultPlayerSnapshot>> inFlightSnapshots;
	private final Map<UUID, Long> playerSessionVersions;
	private final PaperVaultPlayerStateCache stateCache;
	private final String backendInstanceId;
	private static final int MUTATING_RPC_MAX_ATTEMPTS = 3;
	private final AtomicLong retryScheduledCount;
	private final AtomicLong retryExhaustedCount;
	private final AtomicLong retryRecoveredCount;

	public PaperVaultGateway(JavaPlugin plugin, LunaLogger logger, PluginMessageBus<Player, Player> bus, long requestTimeoutMillis) {
		this.plugin = plugin;
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.pendingRequests = new ConcurrentHashMap<>();
		this.inFlightSnapshots = new ConcurrentHashMap<>();
		this.playerSessionVersions = new ConcurrentHashMap<>();
		this.stateCache = new PaperVaultPlayerStateCache();
		this.backendInstanceId = plugin.getServer().getName() + "-" + UUID.randomUUID();
		this.retryScheduledCount = new AtomicLong();
		this.retryExhaustedCount = new AtomicLong();
		this.retryRecoveredCount = new AtomicLong();
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		playerSessionVersions.merge(player.getUniqueId(), 1L, Long::sum);
		snapshot(player.getUniqueId(), player.getName());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		stateCache.remove(playerId);

		CompletableFuture<VaultPlayerSnapshot> inFlight = inFlightSnapshots.remove(playerId);
		if (inFlight != null && !inFlight.isDone()) {
			inFlight.complete(VaultPlayerSnapshot.empty(playerId, event.getPlayer().getName()));
		}
	}

	public void registerChannels() {
		bus.registerOutgoing(VaultChannels.RPC);
		bus.registerIncoming(VaultChannels.CACHE_SYNC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String kind = reader.readUtf();
			if (!"refresh".equals(kind)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			stateCache.apply(VaultCacheRefresh.readFrom(reader));
			return PluginMessageDispatchResult.HANDLED;
		});
		bus.registerIncoming(VaultChannels.RPC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			VaultRpcResponse response = VaultRpcResponse.readFrom(reader);
			CompletableFuture<VaultRpcResponse> future = pendingRequests.remove(response.correlationId());
			if (future != null) {
				future.complete(response);
			}
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	public void close() {
		bus.unregisterIncoming(VaultChannels.CACHE_SYNC);
		bus.unregisterOutgoing(VaultChannels.RPC);
		bus.unregisterIncoming(VaultChannels.RPC);
		pendingRequests.values().forEach(future -> future.completeExceptionally(new IllegalStateException("LunaVaultBackend đang tắt.")));
		pendingRequests.clear();
		inFlightSnapshots.values().forEach(future -> future.completeExceptionally(new IllegalStateException("LunaVaultBackend đang tắt.")));
		inFlightSnapshots.clear();
	}

	@Override
	public CompletableFuture<VaultPlayerSnapshot> snapshot(UUID playerId, String playerName) {
		if (playerId == null) {
			return CompletableFuture.completedFuture(VaultPlayerSnapshot.empty(null, playerName));
		}

		VaultPlayerSnapshot cached = stateCache.get(playerId);
		if (cached != null) {
			return CompletableFuture.completedFuture(cached);
		}

		CompletableFuture<VaultPlayerSnapshot> existing = inFlightSnapshots.get(playerId);
		if (existing != null) {
			return existing;
		}

		return inFlightSnapshots.computeIfAbsent(playerId, ignored ->
			send(selectCarrier(playerId), new VaultRpcRequest(
				UUID.randomUUID(),
				VaultRpcAction.SNAPSHOT,
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
				1,
				backendInstanceId,
				sessionVersion(playerId),
				null
			)).thenApply(response -> {
				VaultPlayerSnapshot snapshot = response.snapshot();
				if (snapshot != null && snapshot.playerId() != null) {
					stateCache.put(snapshot);
					return snapshot;
				}
				return VaultPlayerSnapshot.empty(playerId, playerName);
			}).exceptionally(exception -> VaultPlayerSnapshot.empty(playerId, playerName))
				.whenComplete((snapshot, throwable) -> inFlightSnapshots.remove(playerId))
		);
	}

	public VaultPlayerSnapshot cachedSnapshot(UUID playerId, String playerName) {
		if (playerId == null) {
			return VaultPlayerSnapshot.empty(null, playerName);
		}

		VaultPlayerSnapshot cached = stateCache.get(playerId);
		if (cached != null) {
			return cached;
		}

		return VaultPlayerSnapshot.empty(playerId, playerName);
	}

	public VaultPlayerSnapshot cachedSnapshot(UUID playerId) {
		if (playerId == null) {
			return null;
		}

		return stateCache.get(playerId);
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		return snapshot(playerId, playerName).thenApply(VaultPlayerSnapshot::balanceMinor);
	}

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		return sendWithRetry(
			() -> new VaultRpcRequest(
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
				1,
				backendInstanceId,
				sessionVersion,
				operationId
			),
			MUTATING_RPC_MAX_ATTEMPTS,
			actorId,
			playerId
		).thenApply(response -> {
			VaultOperationResult result = response.result();
			if (result.success()) {
				updateCachedBalance(playerId, playerName, result.balanceMinor());
			}
			return result;
		});
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		return sendWithRetry(
			() -> new VaultRpcRequest(
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
				1,
				backendInstanceId,
				sessionVersion,
				operationId
			),
			MUTATING_RPC_MAX_ATTEMPTS,
			actorId,
			playerId
		).thenApply(response -> {
			VaultOperationResult result = response.result();
			if (result.success()) {
				updateCachedBalance(playerId, playerName, result.balanceMinor());
			}
			return result;
		});
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(senderId);
		return sendWithRetry(
			() -> new VaultRpcRequest(
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
				1,
				backendInstanceId,
				sessionVersion,
				operationId
			),
			MUTATING_RPC_MAX_ATTEMPTS,
			senderId,
			receiverId
		).thenApply(response -> {
			VaultOperationResult result = response.result();
			if (result.success()) {
				updateCachedBalance(senderId, senderName, result.balanceMinor());
				// Receiver balance is not included in transfer result, so keep lazy refresh for receiver.
				stateCache.remove(receiverId);
			}
			return result;
		});
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		return sendWithRetry(
			() -> new VaultRpcRequest(
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
				1,
				backendInstanceId,
				sessionVersion,
				operationId
			),
			MUTATING_RPC_MAX_ATTEMPTS,
			actorId,
			playerId
		).thenApply(response -> {
			VaultOperationResult result = response.result();
			if (result.success()) {
				updateCachedBalance(playerId, playerName, result.balanceMinor());
			}
			return result;
		});
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
			pageSize,
			backendInstanceId,
			sessionVersion(playerId),
			null
		)).thenApply(VaultRpcResponse::page);
	}

	@Override
	public CompletableFuture<VaultLeaderboardPage> leaderboard(int page, int pageSize) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("Bảng xếp hạng chỉ hỗ trợ trên Velocity."));
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

	private CompletableFuture<VaultRpcResponse> sendWithRetry(Supplier<VaultRpcRequest> requestFactory, int maxAttempts, UUID... preferredPlayers) {
		CompletableFuture<VaultRpcResponse> result = new CompletableFuture<>();
		sendAttempt(requestFactory, result, 1, Math.max(1, maxAttempts), preferredPlayers);
		return result;
	}

	private void sendAttempt(Supplier<VaultRpcRequest> requestFactory, CompletableFuture<VaultRpcResponse> result, int attempt, int maxAttempts, UUID... preferredPlayers) {
		if (result.isDone()) {
			return;
		}

		Player carrier = selectCarrier(preferredPlayers);
		if (carrier == null) {
			logger.warn("LunaVault RPC abort: không có carrier online (attempt " + attempt + "/" + maxAttempts + ").");
			result.completeExceptionally(new IllegalStateException("Không có người chơi online để chuyển tiếp yêu cầu lên Velocity."));
			return;
		}

		send(carrier, requestFactory.get()).whenComplete((response, throwable) -> {
			if (throwable == null) {
				if (attempt > 1) {
					long recovered = retryRecoveredCount.incrementAndGet();
					if (recovered % 25L == 0L) {
						logger.audit("LunaVault RPC recovered sau retry: count=" + recovered + " scheduled=" + retryScheduledCount.get() + " exhausted=" + retryExhaustedCount.get());
					}
				}
				result.complete(response);
				return;
			}

			if (attempt >= maxAttempts || !isRetryable(throwable)) {
				retryExhaustedCount.incrementAndGet();
				Throwable root = unwrap(throwable);
				logger.warn("LunaVault RPC thất bại sau " + attempt + " lần thử: " + root.getClass().getSimpleName() + " - " + root.getMessage());
				result.completeExceptionally(throwable);
				return;
			}

			long scheduled = retryScheduledCount.incrementAndGet();
			if (scheduled % 50L == 0L) {
				logger.audit("LunaVault RPC retry scheduled=" + scheduled + " exhausted=" + retryExhaustedCount.get() + " recovered=" + retryRecoveredCount.get());
			}

			plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task ->
				sendAttempt(requestFactory, result, attempt + 1, maxAttempts, preferredPlayers),
				2L
			);
		});
	}

	private boolean isRetryable(Throwable throwable) {
		Throwable root = unwrap(throwable);

		return root instanceof TimeoutException || root instanceof IllegalStateException;
	}

	private Throwable unwrap(Throwable throwable) {
		Throwable root = throwable;
		while (root instanceof CompletionException && root.getCause() != null) {
			root = root.getCause();
		}
		return root;
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

	private long sessionVersion(UUID playerId) {
		if (playerId == null) {
			return 0L;
		}

		return playerSessionVersions.getOrDefault(playerId, 0L);
	}

	private void updateCachedBalance(UUID playerId, String playerName, long balanceMinor) {
		if (playerId == null) {
			return;
		}

		VaultPlayerSnapshot existing = stateCache.get(playerId);
		String resolvedName = playerName;
		if (resolvedName == null || resolvedName.isBlank()) {
			resolvedName = existing == null ? "" : existing.playerName();
		}
		int rank = existing == null ? 0 : existing.rank();
		stateCache.put(new VaultPlayerSnapshot(playerId, resolvedName == null ? "" : resolvedName, balanceMinor, rank));
	}
}
