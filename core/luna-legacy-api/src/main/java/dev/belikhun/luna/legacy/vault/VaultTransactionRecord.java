package dev.belikhun.luna.legacy.vault;

import dev.belikhun.luna.legacy.string.Strings;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.UUID;

public final class VaultTransactionRecord {
	private final String transactionId;
	private final UUID senderId;
	private final String senderName;
	private final UUID receiverId;
	private final String receiverName;
	private final long amountMinor;
	private final String source;
	private final String details;
	private final long completedAt;

	public VaultTransactionRecord(String transactionId, UUID senderId, String senderName, UUID receiverId, String receiverName, long amountMinor, String source, String details, long completedAt) {
		this.transactionId = transactionId;
		this.senderId = senderId;
		this.senderName = senderName;
		this.receiverId = receiverId;
		this.receiverName = receiverName;
		this.amountMinor = amountMinor;
		this.source = source;
		this.details = details;
		this.completedAt = completedAt;
	}

	public String transactionId() {
		return transactionId;
	}

	public UUID senderId() {
		return senderId;
	}

	public String senderName() {
		return senderName;
	}

	public UUID receiverId() {
		return receiverId;
	}

	public String receiverName() {
		return receiverName;
	}

	public long amountMinor() {
		return amountMinor;
	}

	public String source() {
		return source;
	}

	public String details() {
		return details;
	}

	public long completedAt() {
		return completedAt;
	}

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
		return Strings.isBlank(value) ? null : value;
	}
}
