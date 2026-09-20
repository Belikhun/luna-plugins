package dev.belikhun.luna.vault.api.rpc;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.vault.api.VaultCacheRefresh;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultLeaderboardEntry;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultPlayerStateCache;
import dev.belikhun.luna.vault.api.VaultTransactionKind;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRpcCodecTest {
	private static final UUID PLAYER = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();

	@Test
	void requestRoundTripsWithProtocolHeader() {
		VaultRpcRequest request = new VaultRpcRequest(UUID.randomUUID(), VaultRpcAction.TRANSFER, null, null, PLAYER, "Alice", OTHER, "Bob", 1_234L, "pay", "chi tiết", 0, 1, "survival", UUID.randomUUID());
		PluginMessageWriter writer = PluginMessageWriter.create();
		request.writeTo(writer);

		PluginMessageReader reader = PluginMessageReader.of(writer.toByteArray());

		assertEquals(VaultRpcProtocol.VERSION, VaultRpcProtocol.readVersion(reader));
		assertEquals(request, VaultRpcRequest.readBody(reader));
	}

	@Test
	void resendKeepsOperationIdAndChangesCorrelation() {
		VaultRpcRequest request = new VaultRpcRequest(UUID.randomUUID(), VaultRpcAction.DEPOSIT, null, null, PLAYER, "Alice", null, null, 5L, "shop", null, 0, 1, "lobby", UUID.randomUUID());
		VaultRpcRequest resent = request.resend();

		assertEquals(request.operationId(), resent.operationId());
		assertFalse(request.correlationId().equals(resent.correlationId()));
	}

	@Test
	void responseRoundTripsEveryPart() {
		VaultTransactionRecord record = new VaultTransactionRecord("tx", UUID.randomUUID(), VaultTransactionKind.TRANSFER, PLAYER, "Alice", OTHER, "Bob", 50L, 950L, 50L, "pay", null, 123L);
		VaultRpcResponse response = new VaultRpcResponse(
			UUID.randomUUID(),
			VaultOperationResult.success("ok", 950L, record),
			List.of(new VaultPlayerSnapshot(PLAYER, "Alice", 950L, 2, 77L), new VaultPlayerSnapshot(OTHER, "Bob", 50L, 5, 78L)),
			new VaultTransactionPage(List.of(record), 0, 10, 0, 1),
			new VaultLeaderboardPage(List.of(new VaultLeaderboardEntry(1, PLAYER, "Alice", 950L)), 0, 10, 0, 1)
		);
		PluginMessageWriter writer = PluginMessageWriter.create();
		response.writeTo(writer);

		PluginMessageReader reader = PluginMessageReader.of(writer.toByteArray());
		assertEquals(VaultRpcProtocol.VERSION, VaultRpcProtocol.readVersion(reader));
		VaultRpcResponse decoded = VaultRpcResponse.readBody(reader);

		assertEquals(response.correlationId(), decoded.correlationId());
		assertEquals(response.result(), decoded.result());
		assertEquals(response.snapshots(), decoded.snapshots());
		assertEquals(response.page(), decoded.page());
		assertEquals(response.leaderboard(), decoded.leaderboard());
		assertEquals(950L, decoded.snapshotOf(PLAYER).balanceMinor());
		assertNull(decoded.snapshotOf(UUID.randomUUID()));
	}

	@Test
	void failedResponseCarriesReason() {
		VaultRpcResponse response = VaultRpcResponse.failed(UUID.randomUUID(), VaultFailureReason.NOT_RECORDED, "none");
		PluginMessageWriter writer = PluginMessageWriter.create();
		response.writeTo(writer);
		PluginMessageReader reader = PluginMessageReader.of(writer.toByteArray());
		VaultRpcProtocol.readVersion(reader);

		VaultRpcResponse decoded = VaultRpcResponse.readBody(reader);

		assertFalse(decoded.result().success());
		assertEquals(VaultFailureReason.NOT_RECORDED, decoded.result().failureReason());
		assertTrue(decoded.snapshots().isEmpty());
	}

	@Test
	void cacheKeepsTheNewerVersionAndNeverForgetsOnInvalidate() {
		VaultPlayerStateCache cache = new VaultPlayerStateCache();
		VaultPlayerSnapshot newer = new VaultPlayerSnapshot(PLAYER, "Alice", 900L, 1, 20L);
		VaultPlayerSnapshot older = new VaultPlayerSnapshot(PLAYER, "Alice", 1_000L, 1, 10L);

		assertTrue(cache.put(newer));
		assertFalse(cache.put(older));
		assertEquals(900L, cache.get(PLAYER).balanceMinor());

		cache.apply(new VaultCacheRefresh(true, List.of()));

		assertEquals(900L, cache.get(PLAYER).balanceMinor());
		assertTrue(cache.isStale(PLAYER));

		cache.apply(new VaultCacheRefresh(false, List.of(new VaultPlayerSnapshot(PLAYER, "Alice", 800L, 1, 21L))));

		assertEquals(800L, cache.get(PLAYER).balanceMinor());
		assertFalse(cache.isStale(PLAYER));
	}
}
