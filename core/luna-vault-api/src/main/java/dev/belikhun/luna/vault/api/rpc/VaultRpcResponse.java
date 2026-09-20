package dev.belikhun.luna.vault.api.rpc;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultLeaderboardEntry;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The proxy's answer to one request.
 *
 * {@code result} is always present. {@code snapshots} carries the fresh state
 * of every player the operation touched, so a backend updates its cache from
 * the reply itself instead of waiting for a separate push. The two pages are
 * empty unless the action asked for them.
 */
public record VaultRpcResponse(
	UUID correlationId,
	VaultOperationResult result,
	List<VaultPlayerSnapshot> snapshots,
	VaultTransactionPage page,
	VaultLeaderboardPage leaderboard
) {
	public VaultRpcResponse {
		snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
		page = page == null ? VaultTransactionPage.empty(0, 1) : page;
		leaderboard = leaderboard == null ? VaultLeaderboardPage.empty(0, 1) : leaderboard;
	}

	public static VaultRpcResponse of(UUID correlationId, VaultOperationResult result) {
		return new VaultRpcResponse(correlationId, result, List.of(), null, null);
	}

	public static VaultRpcResponse of(UUID correlationId, VaultOperationResult result, List<VaultPlayerSnapshot> snapshots) {
		return new VaultRpcResponse(correlationId, result, snapshots, null, null);
	}

	public static VaultRpcResponse failed(UUID correlationId, VaultFailureReason reason, String message) {
		return of(correlationId, VaultOperationResult.failed(reason, message, 0L));
	}

	/** The first snapshot, which is the requesting player's for every single-player action. */
	public VaultPlayerSnapshot snapshot() {
		return snapshots.isEmpty() ? null : snapshots.get(0);
	}

	/** The snapshot naming this player, if the reply carried one. */
	public VaultPlayerSnapshot snapshotOf(UUID playerId) {
		if (playerId == null) {
			return null;
		}

		for (VaultPlayerSnapshot snapshot : snapshots) {
			if (playerId.equals(snapshot.playerId())) {
				return snapshot;
			}
		}

		return null;
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeShort(VaultRpcProtocol.VERSION);
		writer.writeUuid(correlationId);
		writer.writeBoolean(result.success());
		writer.writeUtf(result.failureReason().name());
		writer.writeUtf(result.message() == null ? "" : result.message());
		writer.writeLong(result.balanceMinor());
		writer.writeBoolean(result.transaction() != null);

		if (result.transaction() != null) {
			result.transaction().writeTo(writer);
		}

		writer.writeInt(snapshots.size());

		for (VaultPlayerSnapshot snapshot : snapshots) {
			snapshot.writeTo(writer);
		}

		writer.writeInt(page.page());
		writer.writeInt(page.pageSize());
		writer.writeInt(page.maxPage());
		writer.writeInt(page.totalCount());
		writer.writeInt(page.entries().size());

		for (VaultTransactionRecord entry : page.entries()) {
			entry.writeTo(writer);
		}

		writer.writeInt(leaderboard.page());
		writer.writeInt(leaderboard.pageSize());
		writer.writeInt(leaderboard.maxPage());
		writer.writeInt(leaderboard.totalCount());
		writer.writeInt(leaderboard.entries().size());

		for (VaultLeaderboardEntry entry : leaderboard.entries()) {
			writer.writeInt(entry.rank());
			writer.writeBoolean(entry.playerId() != null);

			if (entry.playerId() != null) {
				writer.writeUuid(entry.playerId());
			}

			writer.writeUtf(entry.playerName() == null ? "" : entry.playerName());
			writer.writeLong(entry.balanceMinor());
		}
	}

	/**
	 * Decode a frame whose version has already been read and accepted.
	 *
	 * @param reader positioned just after the protocol version
	 */
	public static VaultRpcResponse readBody(PluginMessageReader reader) {
		UUID correlationId = reader.readUuid();
		boolean success = reader.readBoolean();
		VaultFailureReason reason = parseReason(reader.readUtf());
		String message = reader.readUtf();
		long balanceMinor = reader.readLong();
		VaultTransactionRecord transaction = reader.readBoolean() ? VaultTransactionRecord.readFrom(reader) : null;

		int snapshotCount = Math.max(0, reader.readInt());
		List<VaultPlayerSnapshot> snapshots = new ArrayList<>();

		for (int index = 0; index < snapshotCount; index++) {
			snapshots.add(VaultPlayerSnapshot.readFrom(reader));
		}

		int page = reader.readInt();
		int pageSize = reader.readInt();
		int maxPage = reader.readInt();
		int totalCount = reader.readInt();
		int entryCount = Math.max(0, reader.readInt());
		List<VaultTransactionRecord> entries = new ArrayList<>();

		for (int index = 0; index < entryCount; index++) {
			entries.add(VaultTransactionRecord.readFrom(reader));
		}

		int boardPage = reader.readInt();
		int boardPageSize = reader.readInt();
		int boardMaxPage = reader.readInt();
		int boardTotal = reader.readInt();
		int boardCount = Math.max(0, reader.readInt());
		List<VaultLeaderboardEntry> boardEntries = new ArrayList<>();

		for (int index = 0; index < boardCount; index++) {
			int rank = reader.readInt();
			UUID playerId = reader.readBoolean() ? reader.readUuid() : null;
			String playerName = reader.readUtf();
			long entryBalance = reader.readLong();
			boardEntries.add(new VaultLeaderboardEntry(rank, playerId, playerName, entryBalance));
		}

		VaultOperationResult result = new VaultOperationResult(
			success,
			reason,
			message == null || message.isBlank() ? null : message,
			balanceMinor,
			transaction
		);

		return new VaultRpcResponse(
			correlationId,
			result,
			snapshots,
			new VaultTransactionPage(entries, page, pageSize, maxPage, totalCount),
			new VaultLeaderboardPage(boardEntries, boardPage, boardPageSize, boardMaxPage, boardTotal)
		);
	}

	private static VaultFailureReason parseReason(String name) {
		try {
			return VaultFailureReason.valueOf(name);
		} catch (IllegalArgumentException unknown) {
			return VaultFailureReason.INTERNAL_ERROR;
		}
	}
}
