package dev.belikhun.luna.vault.api.rpc;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.UUID;

/**
 * One question from a backend to the proxy.
 *
 * {@code correlationId} pairs the reply with the waiting future and is fresh
 * per send; {@code operationId} names the business operation and is the same
 * across every resend of it, which is what lets the proxy apply a deposit once
 * however many times the frame arrives. {@code backendName} is the server as
 * the proxy knows it and is informational: replies are routed by where the
 * frame came from, not by what it claims.
 */
public record VaultRpcRequest(
	UUID correlationId,
	VaultRpcAction action,
	UUID actorId,
	String actorName,
	UUID playerId,
	String playerName,
	UUID targetId,
	String targetName,
	long amountMinor,
	String source,
	String details,
	int page,
	int pageSize,
	String backendName,
	UUID operationId
) {
	public void writeTo(PluginMessageWriter writer) {
		writer.writeShort(VaultRpcProtocol.VERSION);
		writer.writeUuid(correlationId);
		writer.writeUtf(action.name());
		writeNullableUuid(writer, actorId);
		writer.writeUtf(nullToEmpty(actorName));
		writeNullableUuid(writer, playerId);
		writer.writeUtf(nullToEmpty(playerName));
		writeNullableUuid(writer, targetId);
		writer.writeUtf(nullToEmpty(targetName));
		writer.writeLong(amountMinor);
		writer.writeUtf(nullToEmpty(source));
		writer.writeBoolean(details != null);

		if (details != null) {
			writer.writeUtf(details);
		}

		writer.writeInt(page);
		writer.writeInt(pageSize);
		writer.writeUtf(nullToEmpty(backendName));
		writeNullableUuid(writer, operationId);
	}

	/**
	 * Decode a frame whose version has already been read and accepted.
	 *
	 * @param reader positioned just after the protocol version
	 */
	public static VaultRpcRequest readBody(PluginMessageReader reader) {
		UUID correlationId = reader.readUuid();
		VaultRpcAction action = VaultRpcAction.valueOf(reader.readUtf());
		UUID actorId = readNullableUuid(reader);
		String actorName = emptyToNull(reader.readUtf());
		UUID playerId = readNullableUuid(reader);
		String playerName = emptyToNull(reader.readUtf());
		UUID targetId = readNullableUuid(reader);
		String targetName = emptyToNull(reader.readUtf());
		long amountMinor = reader.readLong();
		String source = emptyToNull(reader.readUtf());
		String details = reader.readBoolean() ? reader.readUtf() : null;
		int page = reader.readInt();
		int pageSize = reader.readInt();
		String backendName = emptyToNull(reader.readUtf());
		UUID operationId = readNullableUuid(reader);

		return new VaultRpcRequest(
			correlationId,
			action,
			actorId,
			actorName,
			playerId,
			playerName,
			targetId,
			targetName,
			amountMinor,
			source,
			details,
			page,
			pageSize,
			backendName,
			operationId
		);
	}

	/** The same request under a new correlation id, for a resend. */
	public VaultRpcRequest resend() {
		return new VaultRpcRequest(
			UUID.randomUUID(),
			action,
			actorId,
			actorName,
			playerId,
			playerName,
			targetId,
			targetName,
			amountMinor,
			source,
			details,
			page,
			pageSize,
			backendName,
			operationId
		);
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
