package dev.belikhun.luna.vault.api.ledger;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.exception.DatabaseException;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionKind;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import dev.belikhun.luna.vault.api.VaultTransactionSummary;
import dev.belikhun.luna.vault.api.model.VaultAccountRepository;
import dev.belikhun.luna.vault.api.model.VaultTransactionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The network's book of account.
 *
 * Every balance change is one database transaction on one connection: lock
 * the accounts involved (in a fixed order, so two transfers crossing each other
 * cannot deadlock), apply the change as a conditional {@code UPDATE} that
 * refuses to take an account below zero, write the ledger row with the running
 * balances it produced, commit. Nothing is computed in memory and written back,
 * so two processes or two threads cannot overwrite each other's work, and a
 * crash between statements leaves nothing half done.
 *
 * Every mutation carries an operation id. The ledger row keeps it under a
 * unique index, and an operation that arrives a second time (a retried frame,
 * a backend that never saw its reply) is answered with the outcome recorded the
 * first time instead of being applied again. Verdicts the ledger refuses
 * (insufficient funds, a bad amount) are not recorded, because nothing changed
 * and a retry should be judged against the balance as it stands then.
 *
 * Reads are plain queries and go through the repositories; only writes need
 * the connection-level control this class takes.
 */
public final class VaultLedger {
	public static final String DEFAULT_SOURCE = "lunavault";

	private static final int MAX_ATTEMPTS = 6;
	private static final long RETRY_BACKOFF_MILLIS = 40L;

	private static final String MESSAGE_DATABASE_DISABLED = "Database của LunaVault chưa sẵn sàng.";
	private static final String MESSAGE_INVALID_AMOUNT = "Số tiền không hợp lệ.";
	private static final String MESSAGE_INVALID_TARGET = "Số tiền hoặc người chơi không hợp lệ.";
	private static final String MESSAGE_INVALID_BALANCE = "Số dư mới không hợp lệ.";
	private static final String MESSAGE_INSUFFICIENT = "Số dư không đủ.";
	private static final String MESSAGE_INSUFFICIENT_TRANSFER = "Số dư không đủ để chuyển tiền.";
	private static final String MESSAGE_SELF_TRANSFER = "Không thể chuyển tiền cho chính mình.";
	private static final String MESSAGE_DEPOSITED = "Đã cộng tiền thành công.";
	private static final String MESSAGE_WITHDRAWN = "Đã trừ tiền thành công.";
	private static final String MESSAGE_TRANSFERRED = "Đã chuyển tiền thành công.";
	private static final String MESSAGE_ADJUSTED = "Đã cập nhật số dư.";

	private final Database database;
	private final LongSupplier clock;
	private final VaultAccountRepository accounts;
	private final VaultTransactionRepository transactions;
	private final AtomicLong lastVersion;
	private volatile LedgerDialect dialect;

	public VaultLedger(Database database) {
		this(database, System::currentTimeMillis);
	}

	public VaultLedger(Database database, LongSupplier clock) {
		this.database = database;
		this.clock = clock;
		this.accounts = new VaultAccountRepository(database);
		this.transactions = new VaultTransactionRepository(database);
		this.lastVersion = new AtomicLong();
		this.dialect = null;
	}

	/** Whether a real database is behind this ledger, or every read is zero. */
	public boolean enabled() {
		return !(database instanceof NoopDatabase);
	}

	public VaultAccountRepository accounts() {
		return accounts;
	}

	public VaultTransactionRepository transactions() {
		return transactions;
	}

	// ------------------------------------------------------------------ reads

	/**
	 * A player's account without creating one.
	 *
	 * Right for anything that is merely looking, such as the console browsing the
	 * player directory, which must not leave empty accounts behind it.
	 */
	public Optional<VaultPlayerSnapshot> find(UUID playerId) {
		if (!enabled() || playerId == null) {
			return Optional.empty();
		}

		return accounts.snapshot(playerId).map(this::stamp);
	}

	/**
	 * A player's account, created empty when missing.
	 *
	 * Right for a player who is here: they will hold money soon, and a rank needs
	 * a row to be ranked.
	 */
	public VaultPlayerSnapshot snapshot(UUID playerId, String playerName) {
		if (!enabled() || playerId == null) {
			return VaultPlayerSnapshot.empty(playerId, playerName);
		}

		ensureAccount(playerId, playerName);

		VaultPlayerSnapshot snapshot = accounts.snapshot(playerId)
			.orElse(VaultPlayerSnapshot.empty(playerId, playerName));

		return stamp(refreshName(snapshot, playerName));
	}

	/**
	 * Keep the stored name current when a read arrives with a newer one.
	 *
	 * A player who renamed keeps their account; the row learns the new name
	 * the first time they are seen. One conditional statement, so a read with
	 * the same name costs no write at all.
	 */
	private VaultPlayerSnapshot refreshName(VaultPlayerSnapshot snapshot, String playerName) {
		String normalized = VaultAccountRepository.normalizePlayerName(playerName);

		if (normalized.isBlank() || normalized.equals(snapshot.playerName())) {
			return snapshot;
		}

		database.update(
			"UPDATE vault_accounts SET player_name = ?, updated_at = ? WHERE player_uuid = ? AND player_name <> ?",
			List.of(normalized, clock.getAsLong(), snapshot.playerId().toString(), normalized)
		);

		return new VaultPlayerSnapshot(snapshot.playerId(), normalized, snapshot.balanceMinor(), snapshot.rank(), snapshot.version());
	}

	public long balance(UUID playerId, String playerName) {
		return snapshot(playerId, playerName).balanceMinor();
	}

	public int accountCount() {
		if (!enabled()) {
			return 0;
		}

		return accounts.accountCount();
	}

	public VaultLeaderboardPage leaderboard(int page, int pageSize) {
		if (!enabled()) {
			return VaultLeaderboardPage.empty(page, pageSize);
		}

		return accounts.leaderboard(page, pageSize);
	}

	public VaultTransactionPage history(UUID playerId, int page, int pageSize) {
		if (!enabled() || playerId == null) {
			return VaultTransactionPage.empty(page, pageSize);
		}

		return transactions.pageForPlayer(playerId, page, pageSize);
	}

	public VaultTransactionSummary summary(UUID playerId) {
		if (!enabled() || playerId == null) {
			return VaultTransactionSummary.empty();
		}

		return transactions.summaryForPlayer(playerId);
	}

	/**
	 * The recorded outcome of an operation, if it was ever applied.
	 *
	 * @param operationId the idempotency key the request carried
	 * @param subjectId the player whose balance the caller asked about
	 */
	public Optional<LedgerResult> replay(UUID operationId, UUID subjectId) {
		if (!enabled() || operationId == null) {
			return Optional.empty();
		}

		return transactions.findByOperationId(operationId).map(record -> replayed(record, subjectId));
	}

	/**
	 * A fresh, versioned snapshot of every listed account.
	 *
	 * Used after a commit to describe what the operation left behind, and by the
	 * proxy to answer a lookup.
	 */
	public List<VaultPlayerSnapshot> snapshots(UUID... playerIds) {
		List<VaultPlayerSnapshot> result = new ArrayList<>();

		if (!enabled() || playerIds == null) {
			return result;
		}

		for (UUID playerId : playerIds) {
			if (playerId == null) {
				continue;
			}

			accounts.snapshot(playerId).map(this::stamp).ifPresent(result::add);
		}

		return result;
	}

	/**
	 * Stamp a snapshot with a version newer than any stamped before.
	 *
	 * The value is wall-clock based so that a proxy restart does not start it
	 * over below what the backends already hold, and forced strictly increasing
	 * within the process so that two stamps in the same millisecond still order.
	 */
	public VaultPlayerSnapshot stamp(VaultPlayerSnapshot snapshot) {
		return snapshot.withVersion(nextVersion());
	}

	private long nextVersion() {
		long now = clock.getAsLong();

		return lastVersion.updateAndGet(previous -> Math.max(previous + 1L, now));
	}

	// ------------------------------------------------------------------ audit

	/**
	 * Reconcile one account against its ledger rows.
	 *
	 * @return the audit of that single account, or of nothing when it has no row
	 */
	public LedgerAudit audit(UUID playerId) {
		if (!enabled() || playerId == null) {
			return new LedgerAudit(0, 0, 0L, List.of());
		}

		Optional<VaultPlayerSnapshot> account = accounts.snapshot(playerId);

		if (account.isEmpty()) {
			return new LedgerAudit(0, 0, 0L, List.of());
		}

		List<LedgerAudit.Mismatch> mismatches = new ArrayList<>();
		int withoutHistory = reconcile(account.get(), mismatches) ? 0 : 1;

		return new LedgerAudit(1, withoutHistory, account.get().balanceMinor(), mismatches);
	}

	/**
	 * Reconcile every account, richest first, up to a limit.
	 *
	 * One query per account, so a large table takes a while; this is an
	 * operator's tool, run from a console, not something the economy does on
	 * its own schedule.
	 */
	public LedgerAudit auditAll(int limit) {
		if (!enabled()) {
			return new LedgerAudit(0, 0, 0L, List.of());
		}

		int safeLimit = Math.max(1, Math.min(10_000, limit));
		VaultLeaderboardPage page = accounts.leaderboard(0, safeLimit);
		List<LedgerAudit.Mismatch> mismatches = new ArrayList<>();
		int checked = 0;
		int withoutHistory = 0;
		long total = 0L;

		for (var entry : page.entries()) {
			VaultPlayerSnapshot snapshot = new VaultPlayerSnapshot(entry.playerId(), entry.playerName(), entry.balanceMinor(), entry.rank());
			checked++;
			total += entry.balanceMinor();

			if (!reconcile(snapshot, mismatches)) {
				withoutHistory++;
			}
		}

		return new LedgerAudit(checked, withoutHistory, total, mismatches);
	}

	/** @return false when the account has no row carrying a running balance for it */
	private boolean reconcile(VaultPlayerSnapshot account, List<LedgerAudit.Mismatch> mismatches) {
		Optional<VaultTransactionRecord> last = transactions.lastWithRunningBalance(account.playerId());

		if (last.isEmpty()) {
			return false;
		}

		Long expected = last.get().balanceAfterFor(account.playerId());

		if (expected == null) {
			return false;
		}

		if (expected != account.balanceMinor()) {
			mismatches.add(new LedgerAudit.Mismatch(
				account.playerId(),
				account.playerName(),
				account.balanceMinor(),
				expected,
				last.get().transactionId(),
				last.get().completedAt()
			));
		}

		return true;
	}

	// ----------------------------------------------------------------- writes

	/**
	 * Create money into an account.
	 *
	 * @param actorId who granted it, or null for the system
	 */
	public LedgerResult deposit(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!enabled()) {
			return LedgerResult.failed(VaultFailureReason.DATABASE_DISABLED, MESSAGE_DATABASE_DISABLED, 0L);
		}

		if (playerId == null || amountMinor <= 0L) {
			return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_AMOUNT, 0L);
		}

		return execute(operationId, playerId, transaction -> {
			LedgerAccount account = transaction.lockAccount(playerId, playerName);
			long newBalance = checkedAdd(account.balanceMinor(), amountMinor);

			if (newBalance < 0L) {
				return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_AMOUNT, account.balanceMinor());
			}

			transaction.applyDelta(playerId, account.playerName(), amountMinor, 0L);

			VaultTransactionRecord record = transaction.record(
				operationId,
				VaultTransactionKind.DEPOSIT,
				actorId,
				actorName,
				playerId,
				account.playerName(),
				amountMinor,
				null,
				newBalance,
				source,
				details
			);

			return new LedgerResult(VaultOperationResult.success(MESSAGE_DEPOSITED, newBalance, record), List.of(), false);
		});
	}

	/**
	 * Take money out of an account, refusing to go below zero.
	 *
	 * @param actorId who took it, or null for the system
	 */
	public LedgerResult withdraw(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long amountMinor, String source, String details) {
		if (!enabled()) {
			return LedgerResult.failed(VaultFailureReason.DATABASE_DISABLED, MESSAGE_DATABASE_DISABLED, 0L);
		}

		if (playerId == null || amountMinor <= 0L) {
			return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_AMOUNT, 0L);
		}

		return execute(operationId, playerId, transaction -> {
			LedgerAccount account = transaction.lockAccount(playerId, playerName);

			if (account.balanceMinor() < amountMinor) {
				return LedgerResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, MESSAGE_INSUFFICIENT, account.balanceMinor());
			}

			int updated = transaction.applyDelta(playerId, account.playerName(), -amountMinor, amountMinor);

			if (updated == 0) {
				long current = transaction.readBalance(playerId);
				return LedgerResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, MESSAGE_INSUFFICIENT, current);
			}

			long newBalance = account.balanceMinor() - amountMinor;

			VaultTransactionRecord record = transaction.record(
				operationId,
				VaultTransactionKind.WITHDRAW,
				playerId,
				account.playerName(),
				actorId,
				actorName,
				amountMinor,
				newBalance,
				null,
				source,
				details
			);

			return new LedgerResult(VaultOperationResult.success(MESSAGE_WITHDRAWN, newBalance, record), List.of(), false);
		});
	}

	/** Move money between two players in one atomic step. */
	public LedgerResult transfer(UUID operationId, UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details) {
		if (!enabled()) {
			return LedgerResult.failed(VaultFailureReason.DATABASE_DISABLED, MESSAGE_DATABASE_DISABLED, 0L);
		}

		if (senderId == null || receiverId == null || amountMinor <= 0L) {
			return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_TARGET, 0L);
		}

		if (senderId.equals(receiverId)) {
			long current = enabled() ? balance(senderId, senderName) : 0L;
			return LedgerResult.failed(VaultFailureReason.SELF_TRANSFER, MESSAGE_SELF_TRANSFER, current);
		}

		return execute(operationId, senderId, transaction -> {
			// lock in a fixed order so two transfers crossing each other cannot deadlock
			boolean senderFirst = senderId.toString().compareTo(receiverId.toString()) < 0;
			LedgerAccount sender;
			LedgerAccount receiver;

			if (senderFirst) {
				sender = transaction.lockAccount(senderId, senderName);
				receiver = transaction.lockAccount(receiverId, receiverName);
			} else {
				receiver = transaction.lockAccount(receiverId, receiverName);
				sender = transaction.lockAccount(senderId, senderName);
			}

			if (sender.balanceMinor() < amountMinor) {
				return LedgerResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, MESSAGE_INSUFFICIENT_TRANSFER, sender.balanceMinor());
			}

			long newReceiverBalance = checkedAdd(receiver.balanceMinor(), amountMinor);

			if (newReceiverBalance < 0L) {
				return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_AMOUNT, sender.balanceMinor());
			}

			int debited = transaction.applyDelta(senderId, sender.playerName(), -amountMinor, amountMinor);

			if (debited == 0) {
				long current = transaction.readBalance(senderId);
				return LedgerResult.failed(VaultFailureReason.INSUFFICIENT_FUNDS, MESSAGE_INSUFFICIENT_TRANSFER, current);
			}

			transaction.applyDelta(receiverId, receiver.playerName(), amountMinor, 0L);

			long newSenderBalance = sender.balanceMinor() - amountMinor;

			VaultTransactionRecord record = transaction.record(
				operationId,
				VaultTransactionKind.TRANSFER,
				senderId,
				sender.playerName(),
				receiverId,
				receiver.playerName(),
				amountMinor,
				newSenderBalance,
				newReceiverBalance,
				source,
				details
			);

			return new LedgerResult(VaultOperationResult.success(MESSAGE_TRANSFERRED, newSenderBalance, record), List.of(), false);
		}, receiverId);
	}

	/**
	 * Set a balance outright.
	 *
	 * The ledger row records the difference, in the direction the money moved,
	 * with the administrator on the other side; a set that changes nothing writes
	 * no row, because nothing happened.
	 */
	public LedgerResult setBalance(UUID operationId, UUID actorId, String actorName, UUID playerId, String playerName, long newBalanceMinor, String source, String details) {
		if (!enabled()) {
			return LedgerResult.failed(VaultFailureReason.DATABASE_DISABLED, MESSAGE_DATABASE_DISABLED, 0L);
		}

		if (playerId == null || newBalanceMinor < 0L) {
			return LedgerResult.failed(VaultFailureReason.INVALID_AMOUNT, MESSAGE_INVALID_BALANCE, 0L);
		}

		return execute(operationId, playerId, transaction -> {
			LedgerAccount account = transaction.lockAccount(playerId, playerName);
			long oldBalance = account.balanceMinor();
			long delta = newBalanceMinor - oldBalance;

			if (delta == 0L) {
				return new LedgerResult(VaultOperationResult.success(MESSAGE_ADJUSTED, newBalanceMinor, null), List.of(), false);
			}

			transaction.applyDelta(playerId, account.playerName(), delta, 0L);

			boolean increased = delta > 0L;
			UUID senderId = increased ? actorId : playerId;
			String senderName = increased ? actorName : account.playerName();
			UUID receiverId = increased ? playerId : actorId;
			String receiverName = increased ? account.playerName() : actorName;

			VaultTransactionRecord record = transaction.record(
				operationId,
				VaultTransactionKind.ADJUST,
				senderId,
				senderName,
				receiverId,
				receiverName,
				Math.abs(delta),
				increased ? null : newBalanceMinor,
				increased ? newBalanceMinor : null,
				source,
				details
			);

			return new LedgerResult(VaultOperationResult.success(MESSAGE_ADJUSTED, newBalanceMinor, record), List.of(), false);
		});
	}

	/**
	 * Write a balance straight in, for a one-off import of another plugin's data.
	 *
	 * No ledger row is written: an imported balance has no counterparty and no
	 * amount that moved, and the import itself is the audit record. The row is
	 * created when missing and its name refreshed when given.
	 */
	public void importBalance(UUID playerId, String playerName, long balanceMinor, long createdAt) {
		if (!enabled() || playerId == null || balanceMinor < 0L) {
			return;
		}

		execute(null, playerId, transaction -> {
			LedgerAccount account = transaction.lockAccount(playerId, playerName, createdAt);
			String name = playerName == null || playerName.isBlank() ? account.playerName() : playerName;

			transaction.applyDelta(playerId, name, balanceMinor - account.balanceMinor(), 0L);

			return new LedgerResult(VaultOperationResult.success(MESSAGE_ADJUSTED, balanceMinor, null), List.of(), false);
		});
	}

	// ----------------------------------------------------------------- engine

	private interface Body {
		LedgerResult run(Transaction transaction) throws SQLException;
	}

	private LedgerResult execute(UUID operationId, UUID subjectId, Body body, UUID... otherIds) {
		DatabaseException lastFailure = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try (Connection connection = database.connection()) {
				LedgerDialect currentDialect = dialectFor(connection);
				Transaction transaction = new Transaction(connection, currentDialect);
				transaction.begin();

				try {
					if (operationId != null) {
						Optional<VaultTransactionRecord> recorded = transaction.findOperation(operationId);

						if (recorded.isPresent()) {
							transaction.rollback();
							return replayed(recorded.get(), subjectId);
						}
					}

					LedgerResult outcome = body.run(transaction);

					if (!outcome.success()) {
						transaction.rollback();
						return outcome;
					}

					transaction.commit();

					return new LedgerResult(outcome.result(), snapshots(involved(subjectId, otherIds)), false);
				} catch (SQLException exception) {
					transaction.rollbackQuietly();

					if (operationId != null && LedgerDialect.duplicateKey(exception)) {
						Optional<LedgerResult> recorded = replay(operationId, subjectId);

						if (recorded.isPresent()) {
							return recorded.get();
						}
					}

					if (LedgerDialect.transient_(exception) && attempt < MAX_ATTEMPTS) {
						lastFailure = new DatabaseException("Ledger transaction contended, retrying.", exception);
						backoff(attempt);
						continue;
					}

					throw new DatabaseException("Ledger transaction failed.", exception);
				}
			} catch (SQLException exception) {
				if (LedgerDialect.transient_(exception) && attempt < MAX_ATTEMPTS) {
					lastFailure = new DatabaseException("Ledger connection failed, retrying.", exception);
					backoff(attempt);
					continue;
				}

				throw new DatabaseException("Ledger connection failed.", exception);
			}
		}

		throw lastFailure == null ? new DatabaseException("Ledger transaction gave up.") : lastFailure;
	}

	private UUID[] involved(UUID subjectId, UUID... otherIds) {
		int extra = otherIds == null ? 0 : otherIds.length;
		UUID[] all = new UUID[extra + 1];
		all[0] = subjectId;

		for (int index = 0; index < extra; index++) {
			all[index + 1] = otherIds[index];
		}

		return all;
	}

	private LedgerResult replayed(VaultTransactionRecord record, UUID subjectId) {
		Long balanceAfter = record.balanceAfterFor(subjectId);
		long balance = balanceAfter == null ? 0L : balanceAfter;
		String message = switch (record.kind()) {
			case DEPOSIT -> MESSAGE_DEPOSITED;
			case WITHDRAW -> MESSAGE_WITHDRAWN;
			case TRANSFER -> MESSAGE_TRANSFERRED;
			case ADJUST -> MESSAGE_ADJUSTED;
		};

		List<VaultPlayerSnapshot> current = snapshots(record.senderId(), record.receiverId());

		return new LedgerResult(VaultOperationResult.success(message, balance, record), current, true);
	}

	private void ensureAccount(UUID playerId, String playerName) {
		if (accounts.find(playerId).isPresent()) {
			return;
		}

		execute(null, playerId, transaction -> {
			transaction.lockAccount(playerId, playerName);
			return new LedgerResult(VaultOperationResult.success(null, 0L, null), List.of(), false);
		});
	}

	private LedgerDialect dialectFor(Connection connection) {
		LedgerDialect known = dialect;

		if (known != null) {
			return known;
		}

		LedgerDialect detected = LedgerDialect.detect(connection);
		dialect = detected;

		return detected;
	}

	private static long checkedAdd(long left, long right) {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException overflow) {
			return -1L;
		}
	}

	private static void backoff(int attempt) {
		long jitter = ThreadLocalRandom.current().nextLong(RETRY_BACKOFF_MILLIS);
		long delay = RETRY_BACKOFF_MILLIS * attempt + jitter;

		try {
			Thread.sleep(delay);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	// ------------------------------------------------------- one transaction

	/** The statements one ledger operation is allowed to run, on its connection. */
	private final class Transaction {
		private final Connection connection;
		private final LedgerDialect transactionDialect;

		private Transaction(Connection connection, LedgerDialect transactionDialect) {
			this.connection = connection;
			this.transactionDialect = transactionDialect;
		}

		/**
		 * Open the transaction.
		 *
		 * SQLite gets {@code BEGIN IMMEDIATE}: a deferred transaction that reads
		 * first and writes second is refused outright (not waited for) when another
		 * writer holds the file, which is exactly the shape of every ledger
		 * operation. Taking the write lock up front lets the driver's busy timeout
		 * do its job instead.
		 */
		void begin() throws SQLException {
			if (transactionDialect == LedgerDialect.SQLITE) {
				try (PreparedStatement begin = connection.prepareStatement("BEGIN IMMEDIATE")) {
					begin.execute();
				}

				return;
			}

			connection.setAutoCommit(false);
		}

		void commit() throws SQLException {
			if (transactionDialect == LedgerDialect.SQLITE) {
				try (PreparedStatement commit = connection.prepareStatement("COMMIT")) {
					commit.execute();
				}

				return;
			}

			connection.commit();
		}

		void rollback() throws SQLException {
			if (transactionDialect == LedgerDialect.SQLITE) {
				try (PreparedStatement rollback = connection.prepareStatement("ROLLBACK")) {
					rollback.execute();
				}

				return;
			}

			connection.rollback();
		}

		void rollbackQuietly() {
			try {
				rollback();
			} catch (SQLException ignored) {
				// the connection is about to be closed anyway
			}
		}

		LedgerAccount lockAccount(UUID playerId, String playerName) throws SQLException {
			return lockAccount(playerId, playerName, clock.getAsLong());
		}

		/**
		 * Read an account under lock, creating it when missing.
		 *
		 * The insert races only with another creation of the same account; that
		 * one loses on the primary key and simply re-reads.
		 */
		LedgerAccount lockAccount(UUID playerId, String playerName, long createdAt) throws SQLException {
			Optional<LedgerAccount> existing = selectAccount(playerId);

			if (existing.isPresent()) {
				return existing.get();
			}

			String storedName = VaultAccountRepository.normalizePlayerName(playerName);
			long now = clock.getAsLong();

			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO vault_accounts (player_uuid, player_name, balance_minor, created_at, updated_at) VALUES (?, ?, 0, ?, ?)"
			)) {
				insert.setString(1, playerId.toString());
				insert.setString(2, storedName);
				insert.setLong(3, createdAt <= 0L ? now : createdAt);
				insert.setLong(4, now);
				insert.executeUpdate();
			} catch (SQLException exception) {
				if (!LedgerDialect.duplicateKey(exception)) {
					throw exception;
				}
			}

			return selectAccount(playerId).orElse(new LedgerAccount(playerId, storedName, 0L));
		}

		private Optional<LedgerAccount> selectAccount(UUID playerId) throws SQLException {
			String sql = "SELECT player_name, balance_minor FROM vault_accounts WHERE player_uuid = ?"
				+ (transactionDialect.rowLocking() ? " FOR UPDATE" : "");

			try (PreparedStatement select = connection.prepareStatement(sql)) {
				select.setString(1, playerId.toString());

				try (ResultSet rows = select.executeQuery()) {
					if (!rows.next()) {
						return Optional.empty();
					}

					String name = rows.getString("player_name");
					long balance = rows.getLong("balance_minor");

					return Optional.of(new LedgerAccount(playerId, name == null ? "" : name, balance));
				}
			}
		}

		/**
		 * Move a balance by {@code delta}, only if it currently holds at least
		 * {@code minimumBalance}.
		 *
		 * The condition is what keeps an account from going negative when two
		 * withdrawals race: the second one finds the row no longer qualifies and
		 * updates nothing.
		 *
		 * @return the number of rows changed, 0 when the condition failed
		 */
		int applyDelta(UUID playerId, String playerName, long delta, long minimumBalance) throws SQLException {
			try (PreparedStatement update = connection.prepareStatement(
				"UPDATE vault_accounts SET balance_minor = balance_minor + ?, player_name = ?, updated_at = ? WHERE player_uuid = ? AND balance_minor >= ?"
			)) {
				update.setLong(1, delta);
				update.setString(2, VaultAccountRepository.normalizePlayerName(playerName));
				update.setLong(3, clock.getAsLong());
				update.setString(4, playerId.toString());
				update.setLong(5, minimumBalance);

				return update.executeUpdate();
			}
		}

		long readBalance(UUID playerId) throws SQLException {
			return selectAccount(playerId).map(LedgerAccount::balanceMinor).orElse(0L);
		}

		Optional<VaultTransactionRecord> findOperation(UUID operationId) throws SQLException {
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT " + VaultTransactionRepository.COLUMNS + " FROM vault_transactions WHERE operation_id = ?"
			)) {
				select.setString(1, operationId.toString());

				try (ResultSet rows = select.executeQuery()) {
					if (!rows.next()) {
						return Optional.empty();
					}

					return Optional.of(VaultTransactionRepository.readRecord(rows));
				}
			}
		}

		VaultTransactionRecord record(
			UUID operationId,
			VaultTransactionKind kind,
			UUID senderId,
			String senderName,
			UUID receiverId,
			String receiverName,
			long amountMinor,
			Long senderBalanceAfter,
			Long receiverBalanceAfter,
			String source,
			String details
		) throws SQLException {
			long completedAt = clock.getAsLong();
			String normalizedSource = source == null || source.isBlank() ? DEFAULT_SOURCE : source;
			String storedSenderName = nullableStoredName(senderName);
			String storedReceiverName = nullableStoredName(receiverName);
			String transactionId = UUID.randomUUID().toString();

			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO vault_transactions (" + VaultTransactionRepository.COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			)) {
				insert.setString(1, transactionId);
				insert.setString(2, operationId == null ? null : operationId.toString());
				insert.setString(3, kind.name());
				insert.setString(4, senderId == null ? null : senderId.toString());
				insert.setString(5, storedSenderName);
				insert.setString(6, receiverId == null ? null : receiverId.toString());
				insert.setString(7, storedReceiverName);
				insert.setLong(8, amountMinor);
				setNullableLong(insert, 9, senderBalanceAfter);
				setNullableLong(insert, 10, receiverBalanceAfter);
				insert.setString(11, normalizedSource);
				insert.setString(12, details);
				insert.setLong(13, completedAt);
				insert.executeUpdate();
			}

			return new VaultTransactionRecord(
				transactionId,
				operationId,
				kind,
				senderId,
				storedSenderName,
				receiverId,
				storedReceiverName,
				amountMinor,
				senderBalanceAfter,
				receiverBalanceAfter,
				normalizedSource,
				details,
				completedAt
			);
		}

		private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
			if (value == null) {
				statement.setNull(index, java.sql.Types.BIGINT);
				return;
			}

			statement.setLong(index, value);
		}

		private String nullableStoredName(String playerName) {
			String normalized = VaultAccountRepository.normalizePlayerName(playerName);
			return normalized.isBlank() ? null : normalized;
		}
	}
}
