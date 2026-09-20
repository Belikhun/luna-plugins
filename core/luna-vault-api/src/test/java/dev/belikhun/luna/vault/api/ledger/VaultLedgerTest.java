package dev.belikhun.luna.vault.api.ledger;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionKind;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultLedgerTest {
	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID ADMIN = UUID.fromString("99999999-9999-9999-9999-999999999999");

	private Database database;
	private VaultLedger ledger;

	@BeforeEach
	void setUp() throws Exception {
		database = TestDatabases.freshSqlite();
		ledger = new VaultLedger(database);
	}

	@AfterEach
	void tearDown() {
		database.close();
	}

	@Test
	void depositCreatesAccountAndRecordsRunningBalance() {
		LedgerResult result = ledger.deposit(UUID.randomUUID(), ADMIN, "admin", ALICE, "Alice", 1_000L, "test", null);

		assertTrue(result.success());
		assertEquals(1_000L, result.result().balanceMinor());
		assertEquals(VaultTransactionKind.DEPOSIT, result.result().transaction().kind());
		assertEquals(1_000L, result.result().transaction().receiverBalanceAfter());
		assertNull(result.result().transaction().senderBalanceAfter());
		assertEquals(1_000L, ledger.balance(ALICE, "Alice"));
		assertEquals(1, result.snapshots().size());
		assertEquals(1, result.snapshots().get(0).rank());
	}

	@Test
	void withdrawRefusesToOverdraw() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 500L, "test", null);

		LedgerResult tooMuch = ledger.withdraw(UUID.randomUUID(), null, null, ALICE, "Alice", 501L, "test", null);
		LedgerResult exact = ledger.withdraw(UUID.randomUUID(), null, null, ALICE, "Alice", 500L, "test", null);

		assertFalse(tooMuch.success());
		assertEquals(VaultFailureReason.INSUFFICIENT_FUNDS, tooMuch.result().failureReason());
		assertEquals(500L, tooMuch.result().balanceMinor());
		assertTrue(exact.success());
		assertEquals(0L, exact.result().balanceMinor());
		assertEquals(0L, exact.result().transaction().senderBalanceAfter());
	}

	@Test
	void rejectedOperationWritesNothing() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 100L, "test", null);
		UUID operationId = UUID.randomUUID();

		LedgerResult refused = ledger.withdraw(operationId, null, null, ALICE, "Alice", 200L, "test", null);

		assertFalse(refused.success());
		assertTrue(ledger.replay(operationId, ALICE).isEmpty());
		assertEquals(1, ledger.history(ALICE, 0, 10).totalCount());
	}

	@Test
	void transferMovesMoneyAtomicallyAndRecordsBothSides() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 1_000L, "test", null);

		LedgerResult transfer = ledger.transfer(UUID.randomUUID(), ALICE, "Alice", BOB, "Bob", 400L, "pay", "gift");

		assertTrue(transfer.success());
		assertEquals(600L, transfer.result().balanceMinor());
		assertEquals(600L, transfer.result().transaction().senderBalanceAfter());
		assertEquals(400L, transfer.result().transaction().receiverBalanceAfter());
		assertEquals(600L, ledger.balance(ALICE, "Alice"));
		assertEquals(400L, ledger.balance(BOB, "Bob"));
		assertEquals(2, transfer.snapshots().size());

		LedgerResult refused = ledger.transfer(UUID.randomUUID(), BOB, "Bob", ALICE, "Alice", 401L, "pay", null);

		assertEquals(VaultFailureReason.INSUFFICIENT_FUNDS, refused.result().failureReason());
		assertEquals(600L, ledger.balance(ALICE, "Alice"));
		assertEquals(400L, ledger.balance(BOB, "Bob"));
	}

	@Test
	void selfTransferIsRefused() {
		LedgerResult refused = ledger.transfer(UUID.randomUUID(), ALICE, "Alice", ALICE, "Alice", 1L, "pay", null);

		assertEquals(VaultFailureReason.SELF_TRANSFER, refused.result().failureReason());
	}

	@Test
	void sameOperationIdIsAppliedOnce() {
		UUID operationId = UUID.randomUUID();

		LedgerResult first = ledger.deposit(operationId, null, null, ALICE, "Alice", 250L, "shop", null);
		LedgerResult again = ledger.deposit(operationId, null, null, ALICE, "Alice", 250L, "shop", null);
		LedgerResult thirdTime = ledger.deposit(operationId, null, null, ALICE, "Alice", 250L, "shop", null);

		assertTrue(first.success());
		assertFalse(first.replayed());
		assertTrue(again.success());
		assertTrue(again.replayed());
		assertTrue(thirdTime.replayed());
		assertEquals(250L, again.result().balanceMinor());
		assertEquals(first.result().transaction().transactionId(), again.result().transaction().transactionId());
		assertEquals(250L, ledger.balance(ALICE, "Alice"));
		assertEquals(1, ledger.history(ALICE, 0, 10).totalCount());
	}

	@Test
	void replayAnswersWithTheSubjectsRunningBalance() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 1_000L, "test", null);
		UUID operationId = UUID.randomUUID();
		ledger.transfer(operationId, ALICE, "Alice", BOB, "Bob", 300L, "pay", null);

		LedgerResult asSender = ledger.replay(operationId, ALICE).orElseThrow();
		LedgerResult asReceiver = ledger.replay(operationId, BOB).orElseThrow();

		assertEquals(700L, asSender.result().balanceMinor());
		assertEquals(300L, asReceiver.result().balanceMinor());
	}

	@Test
	void setBalanceRecordsTheDifferenceInTheRightDirection() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 1_000L, "test", null);

		LedgerResult down = ledger.setBalance(UUID.randomUUID(), ADMIN, "admin", ALICE, "Alice", 400L, "eco", null);
		LedgerResult same = ledger.setBalance(UUID.randomUUID(), ADMIN, "admin", ALICE, "Alice", 400L, "eco", null);
		LedgerResult up = ledger.setBalance(UUID.randomUUID(), ADMIN, "admin", ALICE, "Alice", 900L, "eco", null);

		assertEquals(VaultTransactionKind.ADJUST, down.result().transaction().kind());
		assertEquals(ALICE, down.result().transaction().senderId());
		assertEquals(600L, down.result().transaction().amountMinor());
		assertEquals(400L, down.result().transaction().senderBalanceAfter());
		assertNull(same.result().transaction());
		assertEquals(ALICE, up.result().transaction().receiverId());
		assertEquals(500L, up.result().transaction().amountMinor());
		assertEquals(900L, ledger.balance(ALICE, "Alice"));
	}

	@Test
	void concurrentWithdrawalsNeverOverdraw() throws Exception {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 10_000L, "test", null);
		int attempts = 40;
		long each = 300L;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<LedgerResult>> futures = new ArrayList<>();

		for (int index = 0; index < attempts; index++) {
			futures.add(pool.submit(() -> {
				start.await();
				return ledger.withdraw(UUID.randomUUID(), null, null, ALICE, "Alice", each, "race", null);
			}));
		}

		start.countDown();
		long successes = 0L;

		for (Future<LedgerResult> future : futures) {
			if (future.get(30L, TimeUnit.SECONDS).success()) {
				successes++;
			}
		}

		pool.shutdownNow();

		assertEquals(10_000L / each, successes);
		assertEquals(10_000L - successes * each, ledger.balance(ALICE, "Alice"));
		assertTrue(ledger.audit(ALICE).clean());
	}

	@Test
	void concurrentDepositsAndWithdrawalsNetToZero() throws Exception {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 5_000L, "test", null);
		int rounds = 30;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Future<LedgerResult>> futures = new ArrayList<>();

		for (int index = 0; index < rounds; index++) {
			futures.add(pool.submit(() -> ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 100L, "stress", null)));
			futures.add(pool.submit(() -> ledger.withdraw(UUID.randomUUID(), null, null, ALICE, "Alice", 100L, "stress", null)));
		}

		int refused = 0;

		for (Future<LedgerResult> future : futures) {
			if (!future.get(30L, TimeUnit.SECONDS).success()) {
				refused++;
			}
		}

		pool.shutdownNow();

		// a withdrawal can only be refused if it ran ahead of enough deposits,
		// which the starting balance makes impossible here
		assertEquals(0, refused);
		assertEquals(5_000L, ledger.balance(ALICE, "Alice"));
		assertTrue(ledger.audit(ALICE).clean());
	}

	@Test
	void auditFlagsATamperedBalance() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 700L, "test", null);
		database.update("UPDATE vault_accounts SET balance_minor = 9999 WHERE player_uuid = ?", List.of(ALICE.toString()));

		LedgerAudit audit = ledger.audit(ALICE);

		assertFalse(audit.clean());
		assertEquals(1, audit.mismatches().size());
		assertEquals(9999L, audit.mismatches().get(0).balanceMinor());
		assertEquals(700L, audit.mismatches().get(0).expectedMinor());
	}

	@Test
	void snapshotsCarryIncreasingVersionsAndRank() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 100L, "test", null);
		ledger.deposit(UUID.randomUUID(), null, null, BOB, "Bob", 900L, "test", null);

		VaultPlayerSnapshot first = ledger.snapshot(ALICE, "Alice");
		VaultPlayerSnapshot second = ledger.snapshot(ALICE, "Alice");

		assertEquals(2, first.rank());
		assertTrue(second.version() > first.version());
		assertTrue(second.supersedes(first));
		assertFalse(first.supersedes(second));
	}

	@Test
	void readingWithANewNameRenamesTheAccount() {
		ledger.deposit(UUID.randomUUID(), null, null, ALICE, "OldName", 100L, "test", null);

		VaultPlayerSnapshot renamed = ledger.snapshot(ALICE, "NewName");

		assertEquals("NewName", renamed.playerName());
		assertEquals("NewName", ledger.find(ALICE).orElseThrow().playerName());
	}

	@Test
	void findDoesNotCreateAndHistoryPages() {
		assertTrue(ledger.find(BOB).isEmpty());
		assertEquals(0, ledger.accountCount());

		for (int index = 0; index < 7; index++) {
			ledger.deposit(UUID.randomUUID(), null, null, ALICE, "Alice", 10L, "test", "#" + index);
		}

		VaultTransactionPage page = ledger.history(ALICE, 1, 3);

		assertEquals(7, page.totalCount());
		assertEquals(3, page.entries().size());
		assertEquals(2, page.maxPage());
		assertNotNull(page.entries().get(0).operationId());
		assertEquals(1, ledger.accountCount());
	}

	@Test
	void importWritesBalanceWithoutALedgerRow() {
		ledger.importBalance(ALICE, "Alice", 4_200L, 1_000L);

		assertEquals(4_200L, ledger.balance(ALICE, "Alice"));
		assertEquals(0, ledger.history(ALICE, 0, 10).totalCount());
		assertEquals(1, ledger.audit(ALICE).accountsWithoutHistory());
	}
}
