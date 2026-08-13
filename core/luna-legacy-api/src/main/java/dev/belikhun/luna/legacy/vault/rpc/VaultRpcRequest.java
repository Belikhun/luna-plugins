package dev.belikhun.luna.legacy.vault.rpc;

import dev.belikhun.luna.legacy.string.Strings;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.UUID;

public final class VaultRpcRequest {
	private final UUID correlationId;
	private final VaultRpcAction action;
	private final UUID actorId;
	private final String actorName;
	private final UUID playerId;
	private final String playerName;
	private final UUID targetId;
	private final String targetName;
	private final long amountMinor;
	private final String source;
	private final String details;
	private final int page;
	private final int pageSize;
	private final String backendId;
	private final long sessionVersion;
	private final UUID operationId;

	public VaultRpcRequest(UUID correlationId, VaultRpcAction action, UUID actorId, String actorName, UUID playerId, String playerName, UUID targetId, String targetName, long amountMinor, String source, String details, int page, int pageSize, String backendId, long sessionVersion, UUID operationId) {
		this.correlationId = correlationId;
		this.action = action;
		this.actorId = actorId;
		this.actorName = actorName;
		this.playerId = playerId;
		this.playerName = playerName;
		this.targetId = targetId;
		this.targetName = targetName;
		this.amountMinor = amountMinor;
		this.source = source;
		this.details = details;
		this.page = page;
		this.pageSize = pageSize;
		this.backendId = backendId;
		this.sessionVersion = sessionVersion;
		this.operationId = operationId;
	}

	public UUID correlationId() {
		return correlationId;
	}

	public VaultRpcAction action() {
		return action;
	}

	public UUID actorId() {
		return actorId;
	}

	public String actorName() {
		return actorName;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public UUID targetId() {
		return targetId;
	}

	public String targetName() {
		return targetName;
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

	public int page() {
		return page;
	}

	public int pageSize() {
		return pageSize;
	}

	public String backendId() {
		return backendId;
	}

	public long sessionVersion() {
		return sessionVersion;
	}

	public UUID operationId() {
		return operationId;
	}

	public void writeTo(PluginMessageWriter writer) {
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
		writer.writeUtf(nullToEmpty(backendId));
		writer.writeLong(sessionVersion);
		writeNullableUuid(writer, operationId);
	}

	public static VaultRpcRequest readFrom(PluginMessageReader reader) {
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
		String backendId = emptyToNull(reader.readUtf());
		long sessionVersion = reader.readLong();
		UUID operationId = readNullableUuid(reader);
		return new VaultRpcRequest(correlationId, action, actorId, actorName, playerId, playerName, targetId, targetName, amountMinor, source, details, page, pageSize, backendId, sessionVersion, operationId);
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
