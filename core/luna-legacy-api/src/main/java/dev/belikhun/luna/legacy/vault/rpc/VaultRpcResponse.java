package dev.belikhun.luna.legacy.vault.rpc;

import dev.belikhun.luna.legacy.string.Strings;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;
import dev.belikhun.luna.legacy.vault.VaultFailureReason;
import dev.belikhun.luna.legacy.vault.VaultOperationResult;
import dev.belikhun.luna.legacy.vault.VaultPlayerSnapshot;
import dev.belikhun.luna.legacy.vault.VaultTransactionPage;
import dev.belikhun.luna.legacy.vault.VaultTransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VaultRpcResponse {
	private final UUID correlationId;
	private final VaultOperationResult result;
	private final VaultPlayerSnapshot snapshot;
	private final VaultTransactionPage page;

	public VaultRpcResponse(UUID correlationId, VaultOperationResult result, VaultPlayerSnapshot snapshot, VaultTransactionPage page) {
		this.correlationId = correlationId;
		this.result = result;
		this.snapshot = snapshot;
		this.page = page;
	}

	public UUID correlationId() {
		return correlationId;
	}

	public VaultOperationResult result() {
		return result;
	}

	public VaultPlayerSnapshot snapshot() {
		return snapshot;
	}

	public VaultTransactionPage page() {
		return page;
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeUuid(correlationId);
		writer.writeBoolean(result.success());
		writer.writeUtf(result.failureReason().name());
		writer.writeUtf(result.message() == null ? "" : result.message());
		writer.writeLong(result.balanceMinor());
		writer.writeBoolean(result.transaction() != null);
		if (result.transaction() != null) {
			result.transaction().writeTo(writer);
		}
		writer.writeBoolean(snapshot != null);
		if (snapshot != null) {
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
	}

	public static VaultRpcResponse readFrom(PluginMessageReader reader) {
		UUID correlationId = reader.readUuid();
		boolean success = reader.readBoolean();
		VaultFailureReason reason = VaultFailureReason.valueOf(reader.readUtf());
		String message = reader.readUtf();
		long balanceMinor = reader.readLong();
		VaultTransactionRecord transaction = reader.readBoolean() ? VaultTransactionRecord.readFrom(reader) : null;
		VaultPlayerSnapshot snapshot = reader.readBoolean() ? VaultPlayerSnapshot.readFrom(reader) : null;
		int page = reader.readInt();
		int pageSize = reader.readInt();
		int maxPage = reader.readInt();
		int totalCount = reader.readInt();
		int entryCount = reader.readInt();
		List<VaultTransactionRecord> entries = new ArrayList<>();
		for (int index = 0; index < entryCount; index++) {
			entries.add(VaultTransactionRecord.readFrom(reader));
		}
		return new VaultRpcResponse(
			correlationId,
			new VaultOperationResult(success, reason, Strings.isBlank(message) ? null : message, balanceMinor, transaction),
			snapshot,
			new VaultTransactionPage(entries, page, pageSize, maxPage, totalCount)
		);
	}
}
