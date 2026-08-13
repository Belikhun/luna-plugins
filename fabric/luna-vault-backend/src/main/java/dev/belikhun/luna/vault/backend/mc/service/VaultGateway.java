package dev.belikhun.luna.vault.backend.mc.service;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultPlayerStateCache;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.model.VaultAccountModel;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;
import dev.belikhun.luna.vault.api.model.VaultSyncOutboxRepository;
import dev.belikhun.luna.vault.api.model.VaultTransactionModel;
import dev.belikhun.luna.vault.api.model.VaultTransactionRepository;
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * This backend's half of the network economy, on a mod loader.
 *
 * The proxy owns the money. A backend either reaches the same database directly,
 * or it asks the proxy over a plugin message carried by an online player - the
 * two modes this class switches between on {@code databaseEnabled}, exactly as
 * the Paper gateway does. Everything below the switch is shared: the request
 * shapes, the retry policy and the outbox all live in luna-vault-api.
 *
 * What differs from Paper is only where the runtime comes from: Bukkit's
 * scheduler becomes one scheduled executor plus {@code server.execute}, and its
 * join and quit events become calls the loader's own bootstrap makes - which is
 * why this class is shared by the fabric and neoforge builds unchanged.
 */
public final class VaultGateway implements LunaVaultApi {
	private static final int MUTATING_RPC_MAX_ATTEMPTS = 3;
	private static final String BACKEND_SYNC_SOURCE = "backend-sync";
	private static final long OUTBOX_DRAIN_INTERVAL_MILLIS = 1000L;
	private static final int OUTBOX_BATCH_SIZE = 50;
	private static final int OUTBOX_MAX_ATTEMPTS = 12;
	private static final long OUTBOX_BASE_BACKOFF_MILLIS = 1000L;
	private static final long OUTBOX_MAX_BACKOFF_MILLIS = 120_000L;
	private static final long OUTBOX_ACK_RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
	private static final int OUTBOX_ACK_CLEANUP_LIMIT = 200;
	private static final long RETRY_DELAY_MILLIS = 100L;

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final PluginMessageBus<ServerPlayer, ServerPlayer> bus;
	private final long requestTimeoutMillis;
	private final boolean databaseEnabled;
	private final VaultAccountRepository accountRepository;
	private final VaultTransactionRepository transactionRepository;
	private final VaultSyncOutboxRepository syncOutboxRepository;
	private final Map<UUID, CompletableFuture<VaultRpcResponse>> pendingRequests;
	private final Map<UUID, CompletableFuture<VaultPlayerSnapshot>> inFlightSnapshots;
	private final Map<UUID, Long> playerSessionVersions;
	private final VaultPlayerStateCache stateCache;
	private final String backendInstanceId;
	private final AtomicLong retryScheduledCount;
	private final AtomicLong retryExhaustedCount;
	private final AtomicLong retryRecoveredCount;
	private final AtomicLong outboxAckedCount;
	private final AtomicLong outboxRetriedCount;
	private final AtomicLong outboxFailedCount;
	private final ScheduledExecutorService scheduler;
	private volatile boolean closed;

	public VaultGateway(
		MinecraftServer server,
		String backendName,
		LunaLogger logger,
		PluginMessageBus<ServerPlayer, ServerPlayer> bus,
		Database database,
		long requestTimeoutMillis
	) {
		this.server = server;
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.requestTimeoutMillis = Math.max(1000L, requestTimeoutMillis);
		this.databaseEnabled = !(database instanceof NoopDatabase);
		this.accountRepository = new VaultAccountRepository(database);
		this.transactionRepository = new VaultTransactionRepository(database);
		this.syncOutboxRepository = new VaultSyncOutboxRepository(database);
		this.pendingRequests = new ConcurrentHashMap<>();
		this.inFlightSnapshots = new ConcurrentHashMap<>();
		this.playerSessionVersions = new ConcurrentHashMap<>();
		this.stateCache = new VaultPlayerStateCache();
		this.backendInstanceId = backendName + "-" + UUID.randomUUID();
		this.retryScheduledCount = new AtomicLong();
		this.retryExhaustedCount = new AtomicLong();
		this.retryRecoveredCount = new AtomicLong();
		this.outboxAckedCount = new AtomicLong();
		this.outboxRetriedCount = new AtomicLong();
		this.outboxFailedCount = new AtomicLong();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-vault-backend");
			thread.setDaemon(true);
			return thread;
		});
		this.closed = false;
	}

	/**
	 * A player arriving starts a new session for them.
	 *
	 * The session version is what lets the proxy discard a reply meant for a
	 * connection that has since ended, so it has to move before the first request
	 * of the new one goes out.
	 */
	public void onPlayerJoin(ServerPlayer player) {
		if (player == null) {
			return;
		}

		playerSessionVersions.compute(player.getUUID(), (key, current) -> current == null ? 1L : current + 1L);
		snapshot(player.getUUID(), player.getName().getString());
	}

	public void onPlayerQuit(ServerPlayer player) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		stateCache.remove(playerId);

		CompletableFuture<VaultPlayerSnapshot> inFlight = inFlightSnapshots.remove(playerId);
		if (inFlight != null && !inFlight.isDone()) {
			inFlight.complete(VaultPlayerSnapshot.empty(playerId, player.getName().getString()));
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

		startOutboxDrainTask();
	}

	public void close() {
		closed = true;
		scheduler.shutdownNow();

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

		if (databaseEnabled) {
			VaultPlayerSnapshot snapshot = snapshotNow(playerId, playerName);
			stateCache.put(snapshot);
			return CompletableFuture.completedFuture(snapshot);
		}

		CompletableFuture<VaultPlayerSnapshot> existing = inFlightSnapshots.get(playerId);
		if (existing != null) {
			return existing;
		}

		// The in-flight entry is claimed before the work starts, never inside a
		// computeIfAbsent mapping function.
		//
		// That is not style. The chain below can complete *synchronously* - with no
		// carrier online, `send` answers with an already-failed future - and its
		// `whenComplete` then runs on this thread. Removing the key from inside the
		// mapping function at that moment deadlocks the map against its own bin lock,
		// which on a server thread is a hung tick and a watchdog kill.
		CompletableFuture<VaultPlayerSnapshot> pending = new CompletableFuture<>();
		CompletableFuture<VaultPlayerSnapshot> running = inFlightSnapshots.putIfAbsent(playerId, pending);

		if (running != null) {
			return running;
		}

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
				.whenComplete((snapshot, throwable) -> {
				inFlightSnapshots.remove(playerId);

				// `exceptionally` above already mapped every failure to an empty
				// snapshot, so a throwable here means the handler itself broke
				pending.complete(throwable == null && snapshot != null
					? snapshot
					: VaultPlayerSnapshot.empty(playerId, playerName));
			});

		return pending;
	}

	/**
	 * The balance without waiting for anything.
	 *
	 * A placeholder resolving on the render thread cannot block on the proxy, so
	 * it reads what the last refresh left and takes zero when there is nothing.
	 */
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
		if (databaseEnabled) {
			if (playerId == null) {
				return CompletableFuture.completedFuture(0L);
			}
			return CompletableFuture.completedFuture(balanceNow(playerId, playerName));
		}

		return snapshot(playerId, playerName).thenApply(VaultPlayerSnapshot::balanceMinor);
	}

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return remoteDeposit(actorId, actorName, playerId, playerName, amountMinor, source, details);
		}

		return CompletableFuture.completedFuture(depositNow(actorId, actorName, playerId, playerName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return remoteWithdraw(actorId, actorName, playerId, playerName, amountMinor, source, details);
		}

		return CompletableFuture.completedFuture(withdrawNow(actorId, actorName, playerId, playerName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		if (!databaseEnabled) {
			return remoteTransfer(senderId, senderName, receiverId, receiverName, amountMinor, source, details);
		}

		return CompletableFuture.completedFuture(transferNow(senderId, senderName, receiverId, receiverName, amountMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		if (!databaseEnabled) {
			return remoteSetBalance(actorId, actorName, playerId, playerName, newBalanceMinor, source, details);
		}

		return CompletableFuture.completedFuture(setBalanceNow(actorId, actorName, playerId, playerName, newBalanceMinor, source, details));
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		if (databaseEnabled) {
			if (playerId == null) {
				return CompletableFuture.completedFuture(VaultTransactionPage.empty(page, pageSize));
			}

			return CompletableFuture.completedFuture(transactionRepository.pageForPlayer(playerId, page, pageSize));
		}

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
		if (databaseEnabled) {
			return CompletableFuture.completedFuture(accountRepository.leaderboard(page, pageSize));
		}

		return CompletableFuture.failedFuture(new UnsupportedOperationException("Bảng xếp hạng chỉ hỗ trợ trên Velocity."));
	}

	private CompletableFuture<VaultOperationResult> remoteDeposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		int maxAttempts = maxAttemptsForSource(source);
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
			maxAttempts,
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

	private CompletableFuture<VaultOperationResult> remoteWithdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		int maxAttempts = maxAttemptsForSource(source);
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
			maxAttempts,
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

	private CompletableFuture<VaultOperationResult> remoteTransfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(senderId);
		int maxAttempts = maxAttemptsForSource(source);
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
			maxAttempts,
			senderId,
			receiverId
		).thenApply(response -> {
			VaultOperationResult result = response.result();
			if (result.success()) {
				updateCachedBalance(senderId, senderName, result.balanceMinor());
				stateCache.remove(receiverId);
			}
			return result;
		});
	}

	private CompletableFuture<VaultOperationResult> remoteSetBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		UUID operationId = UUID.randomUUID();
		long sessionVersion = sessionVersion(playerId);
		int maxAttempts = maxAttemptsForSource(source);
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
			maxAttempts,
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

	private synchronized long balanceNow(UUID playerId, String playerName) {
		return snapshotNow(playerId, playerName).balanceMinor();
	}

	private synchronized VaultPlayerSnapshot snapshotNow(UUID playerId, String playerName) {
		String resolvedName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedName);
		refreshKnownName(account, resolvedName);
		return accountRepository.snapshot(playerId)
			.orElse(new VaultPlayerSnapshot(
				playerId,
				VaultAccountRepository.normalizePlayerName(account.getString("player_name", resolvedName)),
				account.getLong("balance_minor", 0L),
				0
			));
	}

	private synchronized VaultOperationResult depositNow(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (playerId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long newBalance = account.getLong("balance_minor", 0L) + amountMinor;
		touchAccount(account, resolvedPlayerName, newBalance);
		VaultTransactionRecord transaction = recordTransaction(actorId, actorName, playerId, resolvedPlayerName, amountMinor, source, details);
		updateCachedBalance(playerId, resolvedPlayerName, newBalance);
		enqueueBalanceSync(actorId, actorName, playerId, resolvedPlayerName, newBalance, source, details);
		return VaultOperationResult.success("Đã cộng tiền thành công.", newBalance, transaction);
	}

	private synchronized VaultOperationResult withdrawNow(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (playerId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long currentBalance = account.getLong("balance_minor", 0L);
		if (currentBalance < amountMinor) {
			return VaultOperationResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, "Số dư không đủ.", currentBalance);
		}

		long newBalance = currentBalance - amountMinor;
		touchAccount(account, resolvedPlayerName, newBalance);
		VaultTransactionRecord transaction = recordTransaction(playerId, resolvedPlayerName, actorId, actorName, amountMinor, source, details);
		updateCachedBalance(playerId, resolvedPlayerName, newBalance);
		enqueueBalanceSync(actorId, actorName, playerId, resolvedPlayerName, newBalance, source, details);
		return VaultOperationResult.success("Đã trừ tiền thành công.", newBalance, transaction);
	}

	private synchronized VaultOperationResult transferNow(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		if (senderId == null || receiverId == null || amountMinor <= 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số tiền hoặc người chơi không hợp lệ.", 0L);
		}
		if (senderId.equals(receiverId)) {
			return VaultOperationResult.failed(VaultFailureReason.SELF_TRANSFER, "Không thể chuyển tiền cho chính mình.", balanceNow(senderId, senderName));
		}

		String resolvedSenderName = resolveName(senderId, senderName);
		String resolvedReceiverName = resolveName(receiverId, receiverName);
		VaultAccountModel senderAccount = accountRepository.findOrCreate(senderId, resolvedSenderName);
		VaultAccountModel receiverAccount = accountRepository.findOrCreate(receiverId, resolvedReceiverName);
		long senderBalance = senderAccount.getLong("balance_minor", 0L);
		if (senderBalance < amountMinor) {
			return VaultOperationResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, "Số dư không đủ để chuyển tiền.", senderBalance);
		}

		long newSenderBalance = senderBalance - amountMinor;
		long newReceiverBalance = receiverAccount.getLong("balance_minor", 0L) + amountMinor;
		touchAccount(senderAccount, resolvedSenderName, newSenderBalance);
		touchAccount(receiverAccount, resolvedReceiverName, newReceiverBalance);
		VaultTransactionRecord transaction = recordTransaction(senderId, resolvedSenderName, receiverId, resolvedReceiverName, amountMinor, source, details);
		updateCachedBalance(senderId, resolvedSenderName, newSenderBalance);
		updateCachedBalance(receiverId, resolvedReceiverName, newReceiverBalance);
		enqueueBalanceSync(senderId, resolvedSenderName, senderId, resolvedSenderName, newSenderBalance, source, details);
		enqueueBalanceSync(senderId, resolvedSenderName, receiverId, resolvedReceiverName, newReceiverBalance, source, details);
		return VaultOperationResult.success("Đã chuyển tiền thành công.", newSenderBalance, transaction);
	}

	private synchronized VaultOperationResult setBalanceNow(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		if (playerId == null || newBalanceMinor < 0L) {
			return VaultOperationResult.failed(VaultFailureReason.INVALID_AMOUNT, "Số dư mới không hợp lệ.", 0L);
		}

		String resolvedPlayerName = resolveName(playerId, playerName);
		VaultAccountModel account = accountRepository.findOrCreate(playerId, resolvedPlayerName);
		long oldBalance = account.getLong("balance_minor", 0L);
		touchAccount(account, resolvedPlayerName, newBalanceMinor);
		long delta = Math.abs(newBalanceMinor - oldBalance);
		VaultTransactionRecord transaction = null;
		if (delta > 0L) {
			UUID senderId = newBalanceMinor >= oldBalance ? actorId : playerId;
			String senderName = newBalanceMinor >= oldBalance ? actorName : resolvedPlayerName;
			UUID receiverId = newBalanceMinor >= oldBalance ? playerId : actorId;
			String receiverName = newBalanceMinor >= oldBalance ? resolvedPlayerName : actorName;
			transaction = recordTransaction(senderId, senderName, receiverId, receiverName, delta, source, details);
		}
		updateCachedBalance(playerId, resolvedPlayerName, newBalanceMinor);
		enqueueBalanceSync(actorId, actorName, playerId, resolvedPlayerName, newBalanceMinor, source, details);
		return VaultOperationResult.success("Đã cập nhật số dư.", newBalanceMinor, transaction);
	}

	private void enqueueBalanceSync(UUID actorId, String actorName, UUID playerId, String playerName, long balanceMinor, String source, String details) {
		if (!databaseEnabled || playerId == null || isBackendSyncSource(source)) {
			return;
		}

		long now = System.currentTimeMillis();
		syncOutboxRepository.enqueue(
			UUID.randomUUID(),
			actorId,
			actorName,
			playerId,
			playerName,
			balanceMinor,
			BACKEND_SYNC_SOURCE,
			details,
			now
		);

		schedule(this::drainSyncOutboxOnce, 0L);
	}

	private void startOutboxDrainTask() {
		if (!databaseEnabled) {
			return;
		}

		scheduler.scheduleWithFixedDelay(
			this::drainSyncOutboxOnce,
			OUTBOX_DRAIN_INTERVAL_MILLIS,
			OUTBOX_DRAIN_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	private void drainSyncOutboxOnce() {
		if (!databaseEnabled || closed) {
			return;
		}

		long now = System.currentTimeMillis();
		List<VaultSyncOutboxRepository.PendingSyncOperation> entries = syncOutboxRepository.due(now, OUTBOX_BATCH_SIZE);
		if (entries.isEmpty()) {
			syncOutboxRepository.cleanupAckedOlderThan(now - OUTBOX_ACK_RETENTION_MILLIS, OUTBOX_ACK_CLEANUP_LIMIT);
			return;
		}

		for (VaultSyncOutboxRepository.PendingSyncOperation entry : entries) {
			processOutboxEntry(entry);
		}

		syncOutboxRepository.cleanupAckedOlderThan(now - OUTBOX_ACK_RETENTION_MILLIS, OUTBOX_ACK_CLEANUP_LIMIT);
	}

	private void processOutboxEntry(VaultSyncOutboxRepository.PendingSyncOperation entry) {
		if (entry == null || entry.playerId() == null || entry.operationId() == null) {
			return;
		}

		long now = System.currentTimeMillis();
		syncOutboxRepository.markInFlight(entry.operationId(), now + requestTimeoutMillis + 1000L, now);
		long sessionVersion = sessionVersion(entry.playerId());
		sendWithRetry(
			() -> new VaultRpcRequest(
				UUID.randomUUID(),
				VaultRpcAction.SET_BALANCE,
				entry.actorId(),
				entry.actorName(),
				entry.playerId(),
				entry.playerName(),
				null,
				null,
				entry.balanceMinor(),
				BACKEND_SYNC_SOURCE,
				entry.details(),
				0,
				1,
				backendInstanceId,
				sessionVersion,
				entry.operationId()
			),
			1,
			entry.playerId()
		).whenComplete((response, throwable) -> {
			long completedAt = System.currentTimeMillis();
			int nextAttempt = entry.attemptCount() + 1;
			if (throwable != null) {
				Throwable root = unwrap(throwable);
				handleOutboxRetry(entry, nextAttempt, completedAt, root.getClass().getSimpleName() + ": " + root.getMessage(), null);
				return;
			}

			VaultOperationResult result = response == null ? null : response.result();
			if (result != null && result.success()) {
				syncOutboxRepository.markAcked(entry.operationId(), completedAt);
				long acked = outboxAckedCount.incrementAndGet();
				if (acked % 50L == 0L) {
					logger.audit("LunaVault outbox acked=" + acked + " retried=" + outboxRetriedCount.get() + " failed=" + outboxFailedCount.get());
				}
				return;
			}

			VaultFailureReason reason = result == null ? VaultFailureReason.INTERNAL_ERROR : result.failureReason();
			String message = result == null ? "Sync response rỗng." : String.valueOf(result.message());
			handleOutboxRetry(entry, nextAttempt, completedAt, message, reason);
		});
	}

	private void handleOutboxRetry(VaultSyncOutboxRepository.PendingSyncOperation entry, int nextAttempt, long now, String error, VaultFailureReason reason) {
		if (nextAttempt >= OUTBOX_MAX_ATTEMPTS || isTerminalSyncFailure(reason)) {
			syncOutboxRepository.markFailed(entry.operationId(), nextAttempt, error, now);
			long failed = outboxFailedCount.incrementAndGet();
			logger.warn("LunaVault outbox đánh dấu FAILED op=" + entry.operationId() + " player=" + entry.playerId() + " attempt=" + nextAttempt + " reason=" + error + " totalFailed=" + failed);
			return;
		}

		long retryAt = now + computeBackoffMillis(nextAttempt);
		syncOutboxRepository.markRetry(entry.operationId(), nextAttempt, retryAt, error, now);
		long retried = outboxRetriedCount.incrementAndGet();
		if (retried % 25L == 0L) {
			logger.warn("LunaVault outbox retry=" + retried + " acked=" + outboxAckedCount.get() + " failed=" + outboxFailedCount.get());
		}
	}

	private boolean isTerminalSyncFailure(VaultFailureReason reason) {
		if (reason == null) {
			return false;
		}

		return reason == VaultFailureReason.INVALID_AMOUNT
			|| reason == VaultFailureReason.SELF_TRANSFER
			|| reason == VaultFailureReason.PLAYER_NOT_FOUND;
	}

	private long computeBackoffMillis(int attempt) {
		int safeAttempt = Math.max(1, attempt);
		long multiplier = 1L << Math.min(16, safeAttempt - 1);
		long delay = OUTBOX_BASE_BACKOFF_MILLIS * multiplier;
		return Math.min(OUTBOX_MAX_BACKOFF_MILLIS, delay);
	}

	private void touchAccount(VaultAccountModel account, String playerName, long newBalance) {
		long now = Instant.now().toEpochMilli();
		account
			.set("player_name", VaultAccountRepository.normalizePlayerName(playerName))
			.set("balance_minor", newBalance)
			.set("updated_at", now)
			.save();
	}

	private void refreshKnownName(VaultAccountModel account, String playerName) {
		String normalizedName = VaultAccountRepository.normalizePlayerName(playerName);
		if (normalizedName.isBlank()) {
			return;
		}

		String currentName = VaultAccountRepository.normalizePlayerName(account.getString("player_name", ""));
		if (normalizedName.equals(currentName)) {
			return;
		}

		account
			.set("player_name", normalizedName)
			.set("updated_at", Instant.now().toEpochMilli())
			.save();
	}

	private VaultTransactionRecord recordTransaction(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		long completedAt = Instant.now().toEpochMilli();
		String normalizedSource = source == null || source.isBlank() ? "lunavaultbackend" : source;
		String transactionId = UUID.randomUUID().toString();
		VaultTransactionModel model = transactionRepository.newModel();
		String normalizedSenderName = nullableStoredName(senderName);
		String normalizedReceiverName = nullableStoredName(receiverName);
		model
			.set("transaction_id", transactionId)
			.set("sender_uuid", senderId == null ? null : senderId.toString())
			.set("sender_name", normalizedSenderName)
			.set("receiver_uuid", receiverId == null ? null : receiverId.toString())
			.set("receiver_name", normalizedReceiverName)
			.set("amount_minor", amountMinor)
			.set("source_plugin", normalizedSource)
			.set("details", details)
			.set("completed_at", completedAt)
			.save();
		return new VaultTransactionRecord(transactionId, senderId, normalizedSenderName, receiverId, normalizedReceiverName, amountMinor, normalizedSource, details, completedAt);
	}

	private String resolveName(UUID playerId, String providedName) {
		if (providedName != null && !providedName.isBlank()) {
			return VaultAccountRepository.normalizePlayerName(providedName);
		}

		ServerPlayer online = onlinePlayer(playerId);
		if (online != null) {
			return VaultAccountRepository.normalizePlayerName(online.getName().getString());
		}

		return accountRepository.find(playerId)
			.map(model -> VaultAccountRepository.normalizePlayerName(model.getString("player_name", "")))
			.filter(name -> !name.isBlank())
			.orElse("");
	}

	private String nullableStoredName(String playerName) {
		String normalized = VaultAccountRepository.normalizePlayerName(playerName);
		return normalized.isBlank() ? null : normalized;
	}

	private boolean isBackendSyncSource(String source) {
		return source != null && BACKEND_SYNC_SOURCE.equalsIgnoreCase(source.trim());
	}

	private CompletableFuture<VaultRpcResponse> send(ServerPlayer carrier, VaultRpcRequest request) {
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

		schedule(() -> {
			CompletableFuture<VaultRpcResponse> pending = pendingRequests.remove(request.correlationId());
			if (pending != null && !pending.isDone()) {
				pending.completeExceptionally(new TimeoutException("Yêu cầu LunaVault bị timeout."));
			}
		}, requestTimeoutMillis);
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

		ServerPlayer carrier = selectCarrier(preferredPlayers);
		if (carrier == null) {
			logger.warn("LunaVault RPC abort: không có carrier online (attempt " + attempt + "/" + maxAttempts + ").");
			result.completeExceptionally(new IllegalStateException("Không có người chơi online để chuyển tiếp yêu cầu lên Velocity."));
			return;
		}

		send(carrier, requestFactory.get()).whenComplete((response, throwable) -> {
			if (result.isDone()) {
				return;
			}

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

			schedule(() -> sendAttempt(requestFactory, result, attempt + 1, maxAttempts, preferredPlayers), RETRY_DELAY_MILLIS);
		});
	}

	private int maxAttemptsForSource(String source) {
		if (source != null && "vault".equalsIgnoreCase(source.trim())) {
			return 1;
		}

		return MUTATING_RPC_MAX_ATTEMPTS;
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

	/**
	 * A plugin message can only leave a backend through a player's own connection,
	 * so every request needs one to ride on. The players it is about are tried
	 * first, since their connection is the one most likely to still be there.
	 */
	private ServerPlayer selectCarrier(UUID... preferredPlayers) {
		if (preferredPlayers != null) {
			for (UUID preferred : preferredPlayers) {
				if (preferred == null) {
					continue;
				}

				ServerPlayer online = onlinePlayer(preferred);
				if (online != null) {
					return online;
				}
			}
		}

		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.get(0);
	}

	private ServerPlayer onlinePlayer(UUID playerId) {
		if (playerId == null) {
			return null;
		}

		return server.getPlayerList().getPlayer(playerId);
	}

	private void schedule(Runnable task, long delayMillis) {
		if (closed) {
			return;
		}

		try {
			scheduler.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// the server is shutting down; the pending futures are failed by close()
		}
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
