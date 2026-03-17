package dev.belikhun.luna.vault.api.rpc;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.vault.api.VaultFailureReason;
import dev.belikhun.luna.vault.api.VaultOperationResult;
import dev.belikhun.luna.vault.api.VaultTransactionPage;
import dev.belikhun.luna.vault.api.VaultTransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record VaultRpcResponse(
	UUID correlationId,
	VaultRpcAction action,
	VaultOperationResult result,
	VaultTransactionPage page
) {
	public void writeTo(PluginMessageWriter writer) {
		writer.writeUtf("response");
		writer.writeUuid(correlationId);
		writer.writeUtf(action.name());
		writer.writeBoolean(result.success());
		writer.writeUtf(result.failureReason().name());
		writer.writeUtf(result.message() == null ? "" : result.message());
		writer.writeLong(result.balanceMinor());
		writer.writeBoolean(result.transaction() != null);
		if (result.transaction() != null) {
			result.transaction().writeTo(writer);
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
		VaultRpcAction action = VaultRpcAction.valueOf(reader.readUtf());
		boolean success = reader.readBoolean();
		VaultFailureReason reason = VaultFailureReason.valueOf(reader.readUtf());
		String message = reader.readUtf();
		long balanceMinor = reader.readLong();
		VaultTransactionRecord transaction = reader.readBoolean() ? VaultTransactionRecord.readFrom(reader) : null;
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
			action,
			new VaultOperationResult(success, reason, message == null || message.isBlank() ? null : message, balanceMinor, transaction),
			new VaultTransactionPage(entries, page, pageSize, maxPage, totalCount)
		);
	}
}
