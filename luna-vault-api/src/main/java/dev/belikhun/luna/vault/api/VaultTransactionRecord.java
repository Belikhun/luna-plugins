package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.UUID;

public record VaultTransactionRecord(
	String transactionId,
	UUID senderId,
	String senderName,
	UUID receiverId,
	String receiverName,
	long amountMinor,
	String source,
	String details,
	long completedAt
) {
	public void writeTo(PluginMessageWriter writer) {
		writer.writeUtf(nullToEmpty(transactionId));
		writeNullableUuid(writer, senderId);
		writer.writeUtf(nullToEmpty(senderName));
		writeNullableUuid(writer, receiverId);
		writer.writeUtf(nullToEmpty(receiverName));
		writer.writeLong(amountMinor);
		writer.writeUtf(nullToEmpty(source));
		writer.writeBoolean(details != null);
		if (details != null) {
			writer.writeUtf(details);
		}
		writer.writeLong(completedAt);
	}

	public static VaultTransactionRecord readFrom(PluginMessageReader reader) {
		String transactionId = emptyToNull(reader.readUtf());
		UUID senderId = readNullableUuid(reader);
		String senderName = emptyToNull(reader.readUtf());
		UUID receiverId = readNullableUuid(reader);
		String receiverName = emptyToNull(reader.readUtf());
		long amountMinor = reader.readLong();
		String source = emptyToNull(reader.readUtf());
		String details = reader.readBoolean() ? reader.readUtf() : null;
		long completedAt = reader.readLong();
		return new VaultTransactionRecord(transactionId, senderId, senderName, receiverId, receiverName, amountMinor, source, details, completedAt);
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

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
