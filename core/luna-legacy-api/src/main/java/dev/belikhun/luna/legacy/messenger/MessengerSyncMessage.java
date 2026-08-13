package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public final class MessengerSyncMessage {
	private final int protocolVersion;
	private final MessengerSyncType syncType;
	private final UUID playerId;
	private final MessagingContext activeContext;
	private final UUID lastReplyTargetId;
	private final long updatedAtEpochMs;

	public MessengerSyncMessage(int protocolVersion, MessengerSyncType syncType, UUID playerId, MessagingContext activeContext, UUID lastReplyTargetId, long updatedAtEpochMs) {
		Objects.requireNonNull(syncType, "syncType");
		Objects.requireNonNull(playerId, "playerId");

		this.protocolVersion = protocolVersion;
		this.syncType = syncType;
		this.playerId = playerId;
		this.activeContext = activeContext;
		this.lastReplyTargetId = lastReplyTargetId;
		this.updatedAtEpochMs = updatedAtEpochMs;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public MessengerSyncType syncType() {
		return syncType;
	}

	public UUID playerId() {
		return playerId;
	}

	public MessagingContext activeContext() {
		return activeContext;
	}

	public UUID lastReplyTargetId() {
		return lastReplyTargetId;
	}

	public long updatedAtEpochMs() {
		return updatedAtEpochMs;
	}

	public static final int CURRENT_PROTOCOL = 1;


	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeUtf(syncType.name())
			.writeUuid(playerId)
			.writeBoolean(activeContext != null);
		if (activeContext != null) {
			activeContext.writeTo(writer);
		}
		writer.writeBoolean(lastReplyTargetId != null);
		if (lastReplyTargetId != null) {
			writer.writeUuid(lastReplyTargetId);
		}
		writer.writeLong(updatedAtEpochMs);
	}

	public static MessengerSyncMessage readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		MessengerSyncType syncType = MessengerSyncType.byName(reader.readUtf());
		UUID playerId = reader.readUuid();
		MessagingContext activeContext = reader.readBoolean() ? MessagingContext.readFrom(reader) : null;
		UUID lastReplyTargetId = reader.readBoolean() ? reader.readUuid() : null;
		long updatedAtEpochMs = reader.readLong();
		return new MessengerSyncMessage(protocolVersion, syncType, playerId, activeContext, lastReplyTargetId, updatedAtEpochMs);
	}
}
