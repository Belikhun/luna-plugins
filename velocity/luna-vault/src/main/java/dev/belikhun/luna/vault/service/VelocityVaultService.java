package dev.belikhun.luna.vault.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.profile.UserProfileRepository;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultChannels;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultMoney;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.VaultTransactionSummary;
import dev.belikhun.luna.vault.api.ledger.LedgerResult;
import dev.belikhun.luna.vault.api.ledger.VaultLedger;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The proxy's economy: the one place money changes hands.
 *
 * Every write is a {@link VaultLedger} transaction, run on this service's own
 * threads so that a proxy event thread never waits on the database. The
 * ledger answers with the balances it left behind, and those go two ways: back
 * to whoever asked, and to the backend each affected player is on right now,
 * so the servers' caches move with the money instead of being emptied and
 * refilled.
 *
 * A second delivery of an operation already in flight joins the first rather
 * than queuing a copy; one that arrives after the first committed is answered
 * from the ledger's record. Between the two, the same request can be sent as
 * often as the transport likes and is applied exactly once.
 */
public final class VelocityVaultService implements LunaVaultApi {
	private static final String MESSAGE_INTERNAL = "Yêu cầu kinh tế thất bại ở proxy.";
	private static final String MESSAGE_SHUTDOWN = "LunaVault đang tắt.";

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final VelocityVaultConfig config;
	private final PluginMessageBus<Object, Object> pluginMessagingBus;
	private final UserProfileRepository userProfileRepository;
	private final VaultLedger ledger;
	private final LegacyBalanceImportService legacyBalanceImportService;
	private final ExecutorService executor;
	private final Map<UUID, CompletableFuture<LedgerResult>> inFlightOperations;
	private volatile boolean shuttingDown;

	public VelocityVaultService(ProxyServer proxyServer, Database database, LunaLogger logger, VelocityVaultConfig config, PluginMessageBus<Object, Object> pluginMessagingBus) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("Service");
		this.config = config;
		this.pluginMessagingBus = pluginMessagingBus;
		this.userProfileRepository = new UserProfileRepository(database);
		this.ledger = new VaultLedger(database);
		this.legacyBalanceImportService = new LegacyBalanceImportService(logger, ledger, userProfileRepository);
		this.inFlightOperations = new ConcurrentHashMap<>();
		this.shuttingDown = false;

		AtomicInteger threadIndex = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(config.ledgerThreads(), runnable -> {
			Thread thread = new Thread(runnable, "luna-vault-ledger-" + threadIndex.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	public LegacyBalanceImportService legacyBalanceImportService() {
		return legacyBalanceImportService;
	}

	public VaultLedger ledger() {
		return ledger;
	}

	/** Whether a real database is behind this service, or reads are all zeroes. */
	public boolean databaseEnabled() {
		return ledger.enabled();
	}

	/** Stop taking work; operations already running finish. */
	public void shutdown() {
		shuttingDown = true;
		executor.shutdown();

		try {
			if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
	}

	// ------------------------------------------------------------------ reads

	@Override
	public CompletableFuture<VaultPlayerSnapshot> snapshot(UUID playerId, String playerName) {
		if (!ledger.enabled() || playerId == null) {
			return CompletableFuture.completedFuture(VaultPlayerSnapshot.empty(playerId, playerName));
		}

		return read(() -> ledger.snapshot(playerId, resolveName(playerId, playerName)), VaultPlayerSnapshot.empty(playerId, playerName));
	}

	@Override
	public CompletableFuture<Long> balance(UUID playerId, String playerName) {
		return snapshot(playerId, playerName).thenApply(VaultPlayerSnapshot::balanceMinor);
	}

	@Override
	public CompletableFuture<VaultLeaderboardPage> leaderboard(int page, int pageSize) {
		return read(() -> ledger.leaderboard(page, pageSize), VaultLeaderboardPage.empty(page, pageSize));
	}

	@Override
	public CompletableFuture<VaultTransactionPage> history(UUID playerId, int page, int pageSize) {
		return read(() -> ledger.history(playerId, page, pageSize), VaultTransactionPage.empty(page, pageSize));
	}

	/**
	 * Read a player's account without creating one.
	 *
	 * {@link #snapshot(UUID, String)} creates a missing row, which is right for a
	 * player who is about to spend money and wrong for a console that is merely
	 * looking: browsing the player directory must not leave a trail of empty
	 * accounts behind it.
	 */
	public Optional<VaultPlayerSnapshot> findSnapshot(UUID playerId) {
		return ledger.find(playerId);
	}

	/** Lifetime transaction totals for a player. */
	public VaultTransactionSummary summary(UUID playerId) {
		return ledger.summary(playerId);
	}

	/** How many accounts exist, so a rank can be shown as "3 of 19". */
	public int accountCount() {
		return ledger.accountCount();
	}

	/** The recorded outcome of an operation, for a backend settling a lost reply. */
	public CompletableFuture<Optional<LedgerResult>> lookup(UUID operationId, UUID subjectId) {
		if (!ledger.enabled() || operationId == null) {
			return CompletableFuture.completedFuture(Optional.empty());
		}

		CompletableFuture<LedgerResult> running = inFlightOperations.get(operationId);

		if (running != null) {
			return running.thenApply(Optional::of);
		}

		return read(() -> ledger.replay(operationId, subjectId), Optional.empty());
	}

	// ----------------------------------------------------------------- writes

	@Override
	public CompletableFuture<VaultOperationResult> deposit(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return deposit(UUID.randomUUID(), actorId, actorName, playerId, playerName, amountMinor, source, details).thenApply(LedgerResult::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> withdraw(UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return withdraw(UUID.randomUUID(), actorId, actorName, playerId, playerName, amountMinor, source, details).thenApply(LedgerResult::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> transfer(UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		return transfer(UUID.randomUUID(), senderId, senderName, receiverId, receiverName, amountMinor, source, details).thenApply(LedgerResult::result);
	}

	@Override
	public CompletableFuture<VaultOperationResult> setBalance(UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		return setBalance(UUID.randomUUID(), actorId, actorName, playerId, playerName, newBalanceMinor, source, details).thenApply(LedgerResult::result);
	}

	public CompletableFuture<LedgerResult> deposit(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return perform(operationId, () -> ledger.deposit(operationId, actorId, actorName, playerId, resolveName(playerId, playerName), amountMinor, source, details));
	}

	public CompletableFuture<LedgerResult> withdraw(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		return perform(operationId, () -> ledger.withdraw(operationId, actorId, actorName, playerId, resolveName(playerId, playerName), amountMinor, source, details));
	}

	public CompletableFuture<LedgerResult> transfer(UUID operationId, UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		return perform(operationId, () -> ledger.transfer(operationId, senderId, resolveName(senderId, senderName), receiverId, resolveName(receiverId, receiverName), amountMinor, source, details));
	}

	public CompletableFuture<LedgerResult> setBalance(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		return perform(operationId, () -> ledger.setBalance(operationId, actorId, actorName, playerId, resolveName(playerId, playerName), newBalanceMinor, source, details));
	}

	/** Tell every backend that what it holds may be wrong; used after a bulk import. */
	public void invalidateBackendCaches() {
		broadcastRefresh(new VaultCacheRefresh(true, List.of()), proxyServer.getAllServers());
	}

	// ------------------------------------------------------------- resolution

	/**
	 * Resolve a UUID or a username to an account target.
	 *
	 * The console addresses players by UUID and the commands by name. A UUID
	 * always resolves, to a name off the live roster, the account table or the
	 * profile directory, and to an empty one when none of them knows it, because
	 * a player who has never held money has no row anywhere here, and "no
	 * account, balance zero" is a truthful answer to a caller who already knows
	 * who they are asking about. A name has no such fallback: without a row
	 * there is nothing to turn it into a UUID, so it stays unresolved.
	 */
	public Optional<AccountTarget> resolveReference(String reference) {
		String trimmed = reference == null ? "" : reference.trim();

		if (trimmed.isBlank()) {
			return Optional.empty();
		}

		UUID parsed;

		try {
			parsed = UUID.fromString(trimmed);
		} catch (IllegalArgumentException notAUuid) {
			return resolveTarget(trimmed);
		}

		return Optional.of(new AccountTarget(parsed, resolveName(parsed, null)));
	}

	public Optional<Player> findOnlinePlayer(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		return proxyServer.getAllPlayers().stream()
			.filter(player -> player.getUsername().equalsIgnoreCase(username))
			.findFirst();
	}

	public Optional<AccountTarget> resolveTarget(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		Optional<Player> online = findOnlinePlayer(username);

		if (online.isPresent()) {
			Player player = online.get();
			return Optional.of(new AccountTarget(player.getUniqueId(), player.getUsername()));
		}

		if (!ledger.enabled()) {
			return Optional.empty();
		}

		return ledger.accounts().findByName(username)
			.map(model -> new AccountTarget(
				UUID.fromString(model.getString("player_uuid", "")),
				model.getString("player_name", username)
			));
	}

	public List<String> suggestTargets(String partial) {
		String token = partial == null ? "" : partial.trim();
		LinkedHashSet<String> suggestions = new LinkedHashSet<>();

		proxyServer.getAllPlayers().stream()
			.map(Player::getUsername)
			.filter(name -> token.isBlank() || name.regionMatches(true, 0, token, 0, token.length()))
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.limit(20)
			.forEach(suggestions::add);

		if (ledger.enabled()) {
			ledger.accounts().searchNamesByPrefix(token, 20).forEach(suggestions::add);
		}

		return suggestions.stream().limit(20).toList();
	}

	// ----------------------------------------------------------------- engine

	private <T> CompletableFuture<T> read(Supplier<T> work, T fallback) {
		if (shuttingDown) {
			return CompletableFuture.completedFuture(fallback);
		}

		CompletableFuture<T> future = new CompletableFuture<>();

		try {
			executor.execute(() -> {
				try {
					future.complete(work.get());
				} catch (Throwable throwable) {
					logger.error("Đọc sổ cái LunaVault thất bại.", throwable);
					future.complete(fallback);
				}
			});
		} catch (RejectedExecutionException rejected) {
			future.complete(fallback);
		}

		return future;
	}

	/**
	 * Run one write on the ledger threads and fan its outcome out.
	 *
	 * A second call with an operation id already running joins that run, so a
	 * frame delivered twice within the same few milliseconds produces one
	 * transaction and two identical replies.
	 */
	private CompletableFuture<LedgerResult> perform(UUID operationId, Supplier<LedgerResult> work) {
		if (shuttingDown) {
			return CompletableFuture.completedFuture(LedgerResult.failed(VaultFailureReason.UNAVAILABLE, MESSAGE_SHUTDOWN, 0L));
		}

		CompletableFuture<LedgerResult> future = new CompletableFuture<>();

		if (operationId != null) {
			CompletableFuture<LedgerResult> running = inFlightOperations.putIfAbsent(operationId, future);

			if (running != null) {
				return running;
			}
		}

		Runnable task = () -> {
			try {
				LedgerResult outcome = work.get();

				if (outcome.success()) {
					announce(outcome);
					pushSnapshots(outcome.snapshots());
				}

				future.complete(outcome);
			} catch (Throwable throwable) {
				logger.error("Ghi sổ cái LunaVault thất bại.", throwable);
				future.complete(LedgerResult.failed(VaultFailureReason.INTERNAL_ERROR, MESSAGE_INTERNAL, 0L));
			} finally {
				if (operationId != null) {
					inFlightOperations.remove(operationId, future);
				}
			}
		};

		try {
			executor.execute(task);
		} catch (RejectedExecutionException rejected) {
			if (operationId != null) {
				inFlightOperations.remove(operationId, future);
			}

			future.complete(LedgerResult.failed(VaultFailureReason.UNAVAILABLE, MESSAGE_SHUTDOWN, 0L));
		}

		return future;
	}

	// -------------------------------------------------------------- fan-out

	/**
	 * Deliver fresh snapshots to the servers holding the affected players.
	 *
	 * Only those servers: a backend that has never seen the player has nothing
	 * to correct, and telling every server to forget everything is exactly the
	 * behaviour this service replaced.
	 */
	private void pushSnapshots(Collection<VaultPlayerSnapshot> snapshots) {
		if (pluginMessagingBus == null || snapshots == null || snapshots.isEmpty()) {
			return;
		}

		Map<RegisteredServer, List<VaultPlayerSnapshot>> byServer = new LinkedHashMap<>();

		for (VaultPlayerSnapshot snapshot : snapshots) {
			if (snapshot == null || snapshot.playerId() == null) {
				continue;
			}

			Optional<RegisteredServer> server = proxyServer.getPlayer(snapshot.playerId())
				.flatMap(Player::getCurrentServer)
				.map(connection -> connection.getServer());

			if (server.isEmpty()) {
				continue;
			}

			byServer.computeIfAbsent(server.get(), ignored -> new ArrayList<>()).add(snapshot);
		}

		for (Map.Entry<RegisteredServer, List<VaultPlayerSnapshot>> entry : byServer.entrySet()) {
			broadcastRefresh(new VaultCacheRefresh(false, entry.getValue()), List.of(entry.getKey()));
		}
	}

	private void broadcastRefresh(VaultCacheRefresh refresh, Collection<RegisteredServer> servers) {
		if (pluginMessagingBus == null) {
			return;
		}

		for (RegisteredServer server : servers) {
			try {
				pluginMessagingBus.send(server, VaultChannels.CACHE_SYNC, writer -> {
					writer.writeUtf("refresh");
					refresh.writeTo(writer);
				});
			} catch (RuntimeException exception) {
				logger.warn("Không gửi được cache refresh tới " + server.getServerInfo().getName() + ": " + exception.getMessage());
			}
		}
	}

	private void announce(LedgerResult outcome) {
		VaultTransactionRecord transaction = outcome.result().transaction();

		if (outcome.replayed() || transaction == null) {
			return;
		}

		if (config.transactionLoggingEnabled()) {
			logger.audit(formatTransactionAudit(transaction));
		}

		VelocityVaultConfig.LargeTransactionAlertConfig alertConfig = config.largeTransactionAlert();

		if (!alertConfig.enabled() || transaction.amountMinor() < alertConfig.thresholdMinor()) {
			return;
		}

		String message = "<yellow>⚠ Giao dịch lớn: <white>" + describeActor(transaction.senderName(), transaction.senderId())
			+ "</white> " + LunaCoreVelocity.services().moneyFormat().formatMinor(transaction.amountMinor(), VaultMoney.SCALE) + " <white>"
			+ describeActor(transaction.receiverName(), transaction.receiverId()) + "</white> <gray>(nguồn: "
			+ transaction.source() + ")</gray></yellow>";

		proxyServer.getAllPlayers().stream()
			.filter(player -> player.hasPermission(alertConfig.permission()))
			.forEach(player -> player.sendRichMessage(message));
		logger.warn(stripMiniMessage(message));
	}

	private String formatTransactionAudit(VaultTransactionRecord transaction) {
		StringBuilder builder = new StringBuilder("TX ")
			.append(transaction.transactionId())
			.append(" | ")
			.append(transaction.kind().name())
			.append(" | ")
			.append(describeActor(transaction.senderName(), transaction.senderId()))
			.append(" -> ")
			.append(describeActor(transaction.receiverName(), transaction.receiverId()))
			.append(" | amount=")
			.append(stripMiniMessage(LunaCoreVelocity.services().moneyFormat().formatMinor(transaction.amountMinor(), VaultMoney.SCALE)))
			.append(" | source=")
			.append(transaction.source());

		if (transaction.operationId() != null) {
			builder.append(" | op=").append(transaction.operationId());
		}

		if (transaction.details() != null && !transaction.details().isBlank()) {
			builder.append(" | details=").append(transaction.details());
		}

		return builder.toString();
	}

	private String describeActor(String name, UUID playerId) {
		if (name != null && !name.isBlank()) {
			return name;
		}

		return playerId == null ? "HỆ THỐNG" : playerId.toString();
	}

	private String stripMiniMessage(String input) {
		return input.replaceAll("<[^>]+>", "");
	}

	private String resolveName(UUID playerId, String providedName) {
		if (providedName != null && !providedName.isBlank()) {
			return VaultAccountRepository.normalizePlayerName(providedName);
		}

		if (playerId == null) {
			return "";
		}

		Optional<Player> player = proxyServer.getPlayer(playerId);

		if (player.isPresent()) {
			return VaultAccountRepository.normalizePlayerName(player.get().getUsername());
		}

		if (!ledger.enabled()) {
			return "";
		}

		return ledger.accounts().find(playerId)
			.map(model -> VaultAccountRepository.normalizePlayerName(model.getString("player_name", "")))
			.filter(name -> !name.isBlank())
			.or(() -> userProfileRepository.findByUuid(playerId)
				.map(profile -> VaultAccountRepository.normalizePlayerName(profile.name())))
			.orElse("");
	}

	public record AccountTarget(UUID playerId, String playerName) {
	}
}
