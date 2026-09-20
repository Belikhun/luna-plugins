package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.UUID;

/**
 * One ledger row.
 *
 * Besides who paid whom how much, a row carries the running balance of each
 * side right after it was applied. That is what makes the ledger auditable:
 * a player's balance must equal the last {@code *BalanceAfter} naming them, and
 * a replayed operation can answer with the balance the original left behind.
 * A null {@code operationId} is a row written before idempotency keys existed.
 */
public record VaultTransactionRecord(
	String transactionId,
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
	String details,
	long completedAt
) {
	public void writeTo(PluginMessageWriter writer) {
		writer.writeUtf(nullToEmpty(transactionId));
		writeNullableUuid(writer, operationId);
		writer.writeUtf(kind == null ? VaultTransactionKind.TRANSFER.name() : kind.name());
		writeNullableUuid(writer, senderId);
		writer.writeUtf(nullToEmpty(senderName));
		writeNullableUuid(writer, receiverId);
		writer.writeUtf(nullToEmpty(receiverName));
		writer.writeLong(amountMinor);
		writeNullableLong(writer, senderBalanceAfter);
		writeNullableLong(writer, receiverBalanceAfter);
		writer.writeUtf(nullToEmpty(source));
		writer.writeBoolean(details != null);

		if (details != null) {
			writer.writeUtf(details);
		}

		writer.writeLong(completedAt);
	}

	public static VaultTransactionRecord readFrom(PluginMessageReader reader) {
		String transactionId = emptyToNull(reader.readUtf());
		UUID operationId = readNullableUuid(reader);
		VaultTransactionKind kind = VaultTransactionKind.parse(reader.readUtf());
		UUID senderId = readNullableUuid(reader);
		String senderName = emptyToNull(reader.readUtf());
		UUID receiverId = readNullableUuid(reader);
		String receiverName = emptyToNull(reader.readUtf());
		long amountMinor = reader.readLong();
		Long senderBalanceAfter = readNullableLong(reader);
		Long receiverBalanceAfter = readNullableLong(reader);
		String source = emptyToNull(reader.readUtf());
		String details = reader.readBoolean() ? reader.readUtf() : null;
		long completedAt = reader.readLong();

		return new VaultTransactionRecord(
			transactionId,
			operationId,
			kind,
			senderId,
			senderName,
			receiverId,
			receiverName,
			amountMinor,
			senderBalanceAfter,
			receiverBalanceAfter,
			source,
			details,
			completedAt
		);
	}

	/** The running balance this row left the given player with, when it names them. */
	public Long balanceAfterFor(UUID playerId) {
		if (playerId == null) {
			return null;
		}

		if (playerId.equals(receiverId) && receiverBalanceAfter != null) {
			return receiverBalanceAfter;
		}

		if (playerId.equals(senderId)) {
			return senderBalanceAfter;
		}

		return null;
	}

	private static void writeNullableUuid(PluginMessageWriter writer, UUID value) {
		writer.writeBoolean(value != null);

		if (value != null) {
			writer.writeUuid(value);
		}
	}

	private static UUID readNullableUuid(PluginMessageReader reader) {
		return reader.readBoolean() ? reader.readUuid() : null;
	}

	private static void writeNullableLong(PluginMessageWriter writer, Long value) {
		writer.writeBoolean(value != null);

		if (value != null) {
			writer.writeLong(value);
		}
	}

	private static Long readNullableLong(PluginMessageReader reader) {
		return reader.readBoolean() ? reader.readLong() : null;
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
