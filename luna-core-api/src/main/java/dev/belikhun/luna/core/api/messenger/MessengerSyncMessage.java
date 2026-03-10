package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public record MessengerSyncMessage(
	int protocolVersion,
	MessengerSyncType syncType,
	UUID playerId,
	MessagingContext activeContext,
	UUID lastReplyTargetId,
	long updatedAtEpochMs
) {
	public static final int CURRENT_PROTOCOL = 1;

	public MessengerSyncMessage {
		Objects.requireNonNull(syncType, "syncType");
		Objects.requireNonNull(playerId, "playerId");
	}

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
