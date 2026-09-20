package dev.belikhun.luna.vault.api.client;

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
import dev.belikhun.luna.vault.api.rpc.VaultRpcAction;
import dev.belikhun.luna.vault.api.rpc.VaultRpcProtocol;
import dev.belikhun.luna.vault.api.rpc.VaultRpcRequest;
import dev.belikhun.luna.vault.api.rpc.VaultRpcResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A backend's view of the network economy.
 *
 * The proxy owns the money; this class asks it. Reads come from a local cache
 * the proxy keeps current by pushing every balance it changes, so a scoreboard
 * or a shop title costs nothing and never blocks. Writes are one request each,
 * carried by an online player's connection (or the AMQP bus, when the core has
 * one), and every write carries an operation id so that sending it again is
 * safe: the proxy applies an id once and answers a repeat with the recorded
 * outcome.
 *
 * A write whose reply never comes is not reported as failed and forgotten. It
 * is reported as timed out, and the client then asks the proxy what became of
 * it, because "the request was lost" and "the money moved and the answer was
 * lost" look identical from here and only the proxy can tell them apart.
 *
 * Nothing here touches the database. A backend with a database of its own has
 * a copy of yesterday's balances at best, and writing money into it was the
 * quickest way this economy had of losing track of who had what.
 *
 * @param <P> the loader's online-player type
 */
public final class VaultClient<P> implements LunaVaultApi {
	private static final String SOURCE_BACKEND = "lunavaultbackend";
	private static final long MIN_TIMEOUT_MILLIS = 1000L;
	private static final long RESEND_BACKOFF_MILLIS = 150L;
	private static final long NO_ROUTE_RETRY_MILLIS = 200L;
	private static final long[] SETTLE_DELAYS_MILLIS = {1000L, 3000L, 7000L};
	private static final long STALE_REFRESH_SPACING_MILLIS = 40L;
	private static final long PROTOCOL_WARN_INTERVAL_MILLIS = 60_000L;

	private static final String MESSAGE_NO_ROUTE = "Không có người chơi online để kết nối tới proxy.";
	private static final String MESSAGE_TIMEOUT = "Proxy không phản hồi kịp; giao dịch sẽ được đối soát lại.";
	private static final String MESSAGE_TRANSPORT = "Không thể gửi yêu cầu tới proxy.";
	private static final String MESSAGE_UNAVAILABLE = "LunaVaultBackend đang tắt.";
	private static final String MESSAGE_PROTOCOL = "Proxy và backend đang chạy hai phiên bản LunaVault khác nhau.";

	private final VaultPlatform<P> platform;
	private final PluginMessageBus<P, P> bus;
	private final LunaLogger logger;
	private final String backendName;
	private final long requestTimeoutMillis;
	private final VaultPlayerStateCache cache;
	private final Map<UUID, PendingRequest> pending;
	private final Map<UUID, CompletableFuture<VaultPlayerSnapshot>> inFlightSnapshots;
	private final ScheduledExecutorService scheduler;
	private final AtomicLong lastProtocolWarning;
	private final AtomicLong timedOutCount;
	private final AtomicLong settledCount;
	private volatile boolean closed;

	public VaultClient(VaultPlatform<P> platform, PluginMessageBus<P, P> bus, LunaLogger logger, String backendName, long requestTimeoutMillis) {
		this.platform = platform;
		this.bus = bus;
		this.logger = logger.scope("Client");
		this.backendName = backendName == null ? "" : backendName;
		this.requestTimeoutMillis = Math.max(MIN_TIMEOUT_MILLIS, requestTimeoutMillis);
		this.cache = new VaultPlayerStateCache();
		this.pending = new ConcurrentHashMap<>();
		this.inFlightSnapshots = new ConcurrentHashMap<>();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-vault-client");
			thread.setDaemon(true);
			return thread;
		});
		this.lastProtocolWarning = new AtomicLong();
		this.timedOutCount = new AtomicLong();
		this.settledCount = new AtomicLong();
		this.closed = false;
	}

	/** What the client is doing right now, for an operator's status command. */
	public Status status() {
		return new Status(cache.size(), pending.size(), inFlightSnapshots.size(), timedOutCount.get(), settledCount.get(), requestTimeoutMillis, closed);
	}

	public record Status(
		int cachedPlayers,
		int pendingRequests,
		int inFlightSnapshots,
		long timedOut,
		long settledAfterTimeout,
		long requestTimeoutMillis,
		boolean closed
	) {
	}

	// -------------------------------------------------------------- lifecycle

	public void registerChannels() {
		bus.registerOutgoing(VaultChannels.RPC);

		// a Vault call blocks the server thread until the reply is in; a reply
		// queued for that same thread would only be read after the wait gave up
		bus.allowAsyncDelivery(VaultChannels.RPC);
		bus.allowAsyncDelivery(VaultChannels.CACHE_SYNC);

		bus.registerIncoming(VaultChannels.CACHE_SYNC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			String kind = reader.readUtf();

			if (!"refresh".equals(kind)) {
				return PluginMessageDispatchResult.HANDLED;
			}

			VaultCacheRefresh refresh = VaultCacheRefresh.readFrom(reader);
			cache.apply(refresh);

			if (refresh.clearAll()) {
				refreshStaleEntries();
			}

			return PluginMessageDispatchResult.HANDLED;
		});

		bus.registerIncoming(VaultChannels.RPC, context -> {
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			int version = VaultRpcProtocol.readVersion(reader);

			if (!VaultRpcProtocol.compatible(version)) {
				warnProtocol(version);
				return PluginMessageDispatchResult.HANDLED;
			}

			VaultRpcResponse response = VaultRpcResponse.readBody(reader);
			PendingRequest request = pending.remove(response.correlationId());

			if (request != null) {
				request.complete(response);
			}

			return PluginMessageDispatchResult.HANDLED;
		});
	}

	public void close() {
		closed = true;
		scheduler.shutdownNow();

		bus.unregisterIncoming(VaultChannels.CACHE_SYNC);
		bus.unregisterIncoming(VaultChannels.RPC);
		bus.unregisterOutgoing(VaultChannels.RPC);

		VaultRpcResponse shutdown = VaultRpcResponse.failed(null, VaultFailureReason.UNAVAILABLE, MESSAGE_UNAVAILABLE);

		for (PendingRequest request : pending.values()) {
			request.complete(shutdown);
		}

		pending.clear();

		for (Map.Entry<UUID, CompletableFuture<VaultPlayerSnapshot>> entry : inFlightSnapshots.entrySet()) {
			entry.getValue().complete(VaultPlayerSnapshot.empty(entry.getKey(), ""));
		}

		inFlightSnapshots.clear();
	}

	/** A player arriving: fetch their balance now, so the first read is not zero. */
	public void onPlayerJoin(P player) {
		if (player == null) {
			return;
		}

		snapshot(platform.idOf(player), platform.nameOf(player));
	}

	public void onPlayerQuit(P player) {
		if (player == null) {
			return;
		}

		UUID playerId = platform.idOf(player);
		cache.remove(playerId);

		CompletableFuture<VaultPlayerSnapshot> inFlight = inFlightSnapshots.remove(playerId);

		if (inFlight != null && !inFlight.isDone()) {
			inFlight.complete(VaultPlayerSnapshot.empty(playerId, platform.nameOf(player)));
		}
	}

	// ------------------------------------------------------------------ reads

	/**
	 * The balance without waiting for anything.
	 *
	 * A placeholder resolving on the render thread cannot block on the proxy, so
	 * it reads what the last refresh left and takes zero when there is nothing.
	 */
	public VaultPlayerSnapshot cachedSnapshot(UUID playerId, String playerName) {
		VaultPlayerSnapshot cached = cache.get(playerId);

		if (cached != null) {
			return cached;
		}

		return VaultPlayerSnapshot.empty(playerId, playerName);
	}

	/** The cached snapshot, or null when this player has none yet. */
	public VaultPlayerSnapshot cachedSnapshot(UUID playerId) {
		return cache.get(playerId);
	}

	@Override
	public CompletableFuture<VaultPlayerSnapshot> snapshot(UUID playerId, String playerName) {
		if (playerId == null) {
			return CompletableFuture.completedFuture(VaultPlayerSnapshot.empty(null, playerName));
		}

		VaultPlayerSnapshot cached = cache.get(playerId);

		if (cached != null) {
			if (cache.isStale(playerId)) {
				fetchSnapshot(playerId, playerName);
			}

			return CompletableFuture.completedFuture(cached);
		}

		return fetchSnapshot(playerId, playerName);
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		return snapshot(playerId, playerName).thenApply(VaultPlayerSnapshot::balanceMinor);
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		VaultRpcRequest request = new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.HISTORY,
			null,
			null,
			playerId,
			null,
			null,
			null,
			0L,
			SOURCE_BACKEND,
			null,
			page,
			pageSize,
			backendName,
			null
		);

		return request(request, true, playerId).thenCompose(response -> {
			if (!response.result().success()) {
				return CompletableFuture.failedFuture(new IllegalStateException(describe(response.result())));
			}

			return CompletableFuture.completedFuture(response.page());
		});
	}

	@Override
	public CompletableFuture<VaultLeaderboardPage> leaderboard(int page, int pageSize) {
		VaultRpcRequest request = new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.LEADERBOARD,
			null,
			null,
			null,
			null,
			null,
			null,
			0L,
			SOURCE_BACKEND,
			null,
			page,
			pageSize,
			backendName,
			null
		);

		return request(request, true).thenCompose(response -> {
			if (!response.result().success()) {
				return CompletableFuture.failedFuture(new IllegalStateException(describe(response.result())));
			}

			return CompletableFuture.completedFuture(response.leaderboard());
		});
	}

	// ----------------------------------------------------------------- writes

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		VaultRpcRequest request = new VaultRpcRequest(
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
			backendName,
			UUID.randomUUID()
		);

		return mutate(request, playerId, actorId);
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		VaultRpcRequest request = new VaultRpcRequest(
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
			backendName,
			UUID.randomUUID()
		);

		return mutate(request, playerId, actorId);
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		VaultRpcRequest request = new VaultRpcRequest(
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
			backendName,
			UUID.randomUUID()
		);

		return mutate(request, senderId, receiverId);
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		VaultRpcRequest request = new VaultRpcRequest(
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
			backendName,
			UUID.randomUUID()
		);

		return mutate(request, playerId, actorId);
	}

	/**
	 * Send a write and turn whatever happens into a result the caller can act on.
	 *
	 * The future never completes exceptionally: a caller that could not reach
	 * the proxy gets a failed result naming the transport reason, which is what
	 * a shop shows the player. A timed-out write additionally schedules a
	 * settlement lookup, so the cache ends up right even though the caller has
	 * already been told the outcome is unknown.
	 */
	private CompletableFuture<VaultOperationResult> mutate(VaultRpcRequest request, UUID... preferredCarriers) {
		return request(request, true, preferredCarriers).thenApply(response -> {
			VaultOperationResult result = response.result();

			if (result.success()) {
				applySnapshots(response.snapshots());
			} else if (result.failureReason() == VaultFailureReason.TIMEOUT) {
				scheduleSettlement(request, 0);
			}

			return result;
		});
	}

	// -------------------------------------------------------------- transport

	private CompletableFuture<VaultPlayerSnapshot> fetchSnapshot(UUID playerId, String playerName) {
		// claim the in-flight slot before any work: the request chain can complete
		// synchronously (no carrier online fails at once), and finishing inside a
		// computeIfAbsent mapping function would deadlock the map on itself
		CompletableFuture<VaultPlayerSnapshot> result = new CompletableFuture<>();
		CompletableFuture<VaultPlayerSnapshot> running = inFlightSnapshots.putIfAbsent(playerId, result);

		if (running != null) {
			return running;
		}

		VaultRpcRequest request = new VaultRpcRequest(
			UUID.randomUUID(),
			VaultRpcAction.SNAPSHOT,
			null,
			null,
			playerId,
			playerName,
			null,
			null,
			0L,
			SOURCE_BACKEND,
			null,
			0,
			1,
			backendName,
			null
		);

		request(request, true, playerId).whenComplete((response, throwable) -> {
			inFlightSnapshots.remove(playerId, result);

			VaultPlayerSnapshot snapshot = null;

			if (throwable == null && response != null && response.result().success()) {
				snapshot = response.snapshotOf(playerId);
			}

			if (snapshot == null) {
				// nothing learned; the cache keeps whatever it had, the caller gets a
				// placeholder that is never stored
				VaultPlayerSnapshot cached = cache.get(playerId);
				result.complete(cached != null ? cached : VaultPlayerSnapshot.empty(playerId, playerName));
				return;
			}

			cache.put(snapshot);
			result.complete(cache.get(playerId) == null ? snapshot : cache.get(playerId));
		});

		return result;
	}

	private void applySnapshots(Collection<VaultPlayerSnapshot> snapshots) {
		if (snapshots == null) {
			return;
		}

		for (VaultPlayerSnapshot snapshot : snapshots) {
			cache.put(snapshot);
		}
	}

	/**
	 * Send a request, resending on transport failure while the time budget lasts.
	 *
	 * The budget is the configured timeout, whole: a request that has waited
	 * that long for a reply is reported as timed out rather than sent again,
	 * because a second copy could not come back any sooner and the caller is
	 * already holding a tick. Resends are for the failures that come back at
	 * once, a bus that refused the frame or a carrier who just left.
	 */
	private CompletableFuture<VaultRpcResponse> request(VaultRpcRequest request, boolean resend, UUID... preferredCarriers) {
		CompletableFuture<VaultRpcResponse> result = new CompletableFuture<>();
		long deadline = System.currentTimeMillis() + requestTimeoutMillis;

		attempt(request, resend, deadline, 1, result, preferredCarriers);

		return result;
	}

	private void attempt(VaultRpcRequest request, boolean resend, long deadline, int attemptNumber, CompletableFuture<VaultRpcResponse> result, UUID... preferredCarriers) {
		if (result.isDone()) {
			return;
		}

		if (closed) {
			result.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.UNAVAILABLE, MESSAGE_UNAVAILABLE));
			return;
		}

		long remaining = deadline - System.currentTimeMillis();

		if (remaining <= 0L) {
			result.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.TIMEOUT, MESSAGE_TIMEOUT));
			return;
		}

		// a carrier is the player whose connection the frame rides; with nobody
		// online the bus is still asked, because the AMQP transport can publish on
		// the server's own behalf and only the plugin-message fallback needs a player
		P carrier = selectCarrier(preferredCarriers);
		PendingRequest pendingRequest = new PendingRequest(request);
		pending.put(request.correlationId(), pendingRequest);

		boolean sent;

		try {
			sent = bus.send(carrier, VaultChannels.RPC, writer -> request.writeTo(writer));
		} catch (RuntimeException exception) {
			if (carrier != null) {
				logger.warn("Không gửi được RPC LunaVault: " + exception.getMessage());
			}

			sent = false;
		}

		if (!sent) {
			pending.remove(request.correlationId());

			if (carrier == null) {
				if (resend && remaining > NO_ROUTE_RETRY_MILLIS * 2L && attemptNumber == 1) {
					schedule(() -> attempt(request.resend(), resend, deadline, attemptNumber + 1, result, preferredCarriers), NO_ROUTE_RETRY_MILLIS);
					return;
				}

				result.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.NO_ROUTE, MESSAGE_NO_ROUTE));
				return;
			}

			if (resend && remaining > RESEND_BACKOFF_MILLIS * 2L) {
				schedule(() -> attempt(request.resend(), resend, deadline, attemptNumber + 1, result, preferredCarriers), RESEND_BACKOFF_MILLIS * attemptNumber);
				return;
			}

			result.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.TRANSPORT_ERROR, MESSAGE_TRANSPORT));
			return;
		}

		ScheduledFuture<?> timeout = schedule(() -> {
			PendingRequest expired = pending.remove(request.correlationId());

			if (expired == null) {
				return;
			}

			long count = timedOutCount.incrementAndGet();

			if (count == 1L || count % 20L == 0L) {
				logger.warn("RPC LunaVault hết hạn chờ (" + request.action() + ", tổng " + count + " lần).");
			}

			expired.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.TIMEOUT, MESSAGE_TIMEOUT));
		}, remaining);

		pendingRequest.armed(timeout);
		pendingRequest.future().whenComplete((response, throwable) -> {
			if (throwable != null) {
				result.complete(VaultRpcResponse.failed(request.correlationId(), VaultFailureReason.INTERNAL_ERROR, String.valueOf(throwable.getMessage())));
				return;
			}

			result.complete(response);
		});
	}

	private P selectCarrier(UUID... preferredCarriers) {
		if (preferredCarriers != null) {
			for (UUID preferred : preferredCarriers) {
				if (preferred == null) {
					continue;
				}

				P online = platform.byId(preferred);

				if (online != null) {
					return online;
				}
			}
		}

		Collection<? extends P> everyone = platform.online();

		if (everyone == null || everyone.isEmpty()) {
			return null;
		}

		return everyone.iterator().next();
	}

	// ------------------------------------------------------------- settlement

	/**
	 * Find out what became of a write whose reply was lost.
	 *
	 * Asked a few times over ten seconds, because the proxy may still be
	 * applying it when the first lookup arrives. A recorded outcome refreshes
	 * the cache; none after the last try means the request never reached the
	 * ledger, and the caller's failure stands.
	 */
	private void scheduleSettlement(VaultRpcRequest original, int step) {
		if (closed || original.operationId() == null || step >= SETTLE_DELAYS_MILLIS.length) {
			if (step >= SETTLE_DELAYS_MILLIS.length) {
				logger.warn("Giao dịch " + original.operationId() + " (" + original.action() + ") không tìm thấy trên proxy sau khi hết hạn chờ; coi như chưa thực hiện.");
			}

			return;
		}

		schedule(() -> {
			VaultRpcRequest lookup = new VaultRpcRequest(
				UUID.randomUUID(),
				VaultRpcAction.LOOKUP,
				null,
				null,
				original.playerId(),
				original.playerName(),
				original.targetId(),
				original.targetName(),
				0L,
				SOURCE_BACKEND,
				null,
				0,
				1,
				backendName,
				original.operationId()
			);

			request(lookup, false, original.playerId(), original.targetId()).thenAccept(response -> {
				if (response.result().success()) {
					applySnapshots(response.snapshots());
					long settled = settledCount.incrementAndGet();
					logger.audit("Giao dịch " + original.operationId() + " (" + original.action() + ") đã được proxy ghi nhận dù backend hết hạn chờ; đã đối soát (" + settled + " lần).");
					return;
				}

				// not recorded yet, or the proxy could not be asked: try again later
				scheduleSettlement(original, step + 1);
			});
		}, SETTLE_DELAYS_MILLIS[step]);
	}

	/** After a bulk invalidation, refetch every online player, spaced out. */
	private void refreshStaleEntries() {
		List<P> players = new ArrayList<>(platform.online());
		long delay = 0L;

		for (P player : players) {
			UUID playerId = platform.idOf(player);

			if (!cache.isStale(playerId)) {
				continue;
			}

			String playerName = platform.nameOf(player);
			schedule(() -> fetchSnapshot(playerId, playerName), delay);
			delay += STALE_REFRESH_SPACING_MILLIS;
		}
	}

	// ---------------------------------------------------------------- helpers

	private ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
		if (closed) {
			return null;
		}

		try {
			return scheduler.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
			return null;
		}
	}

	private void warnProtocol(int version) {
		long now = System.currentTimeMillis();
		long last = lastProtocolWarning.get();

		if (now - last < PROTOCOL_WARN_INTERVAL_MILLIS) {
			return;
		}

		if (lastProtocolWarning.compareAndSet(last, now)) {
			logger.warn(MESSAGE_PROTOCOL + " (proxy=" + version + ", backend=" + VaultRpcProtocol.VERSION + ")");
		}
	}

	private static String describe(VaultOperationResult result) {
		if (result.message() != null && !result.message().isBlank()) {
			return result.message();
		}

		return result.failureReason().name();
	}

	/** One request in flight: its future and the timeout that will fail it. */
	private static final class PendingRequest {
		private final VaultRpcRequest request;
		private final CompletableFuture<VaultRpcResponse> future;
		private volatile ScheduledFuture<?> timeout;

		private PendingRequest(VaultRpcRequest request) {
			this.request = request;
			this.future = new CompletableFuture<>();
			this.timeout = null;
		}

		CompletableFuture<VaultRpcResponse> future() {
			return future;
		}

		void armed(ScheduledFuture<?> scheduledTimeout) {
			this.timeout = scheduledTimeout;
		}

		void complete(VaultRpcResponse response) {
			ScheduledFuture<?> armedTimeout = timeout;

			if (armedTimeout != null) {
				armedTimeout.cancel(false);
			}

			VaultRpcResponse delivered = response.correlationId() == null
				? new VaultRpcResponse(request.correlationId(), response.result(), response.snapshots(), response.page(), response.leaderboard())
				: response;

			future.complete(delivered);
		}
	}
}
