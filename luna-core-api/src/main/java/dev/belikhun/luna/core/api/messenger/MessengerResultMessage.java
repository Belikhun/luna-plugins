package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MessengerResultMessage(
	int protocolVersion,
	UUID correlationId,
	UUID receiverId,
	MessengerResultType resultType,
	String miniMessage,
	Map<String, String> metadata
) {
	public static final int CURRENT_PROTOCOL = 1;

	public MessengerResultMessage {
		Objects.requireNonNull(receiverId, "receiverId");
		Objects.requireNonNull(resultType, "resultType");
		miniMessage = miniMessage == null ? "" : miniMessage;
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeBoolean(correlationId != null);
		if (correlationId != null) {
			writer.writeUuid(correlationId);
		}
		writer
			.writeUuid(receiverId)
			.writeUtf(resultType.name())
			.writeUtf(miniMessage);
		MessengerCodec.writeStringMap(writer, metadata);
	}

	public static MessengerResultMessage readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		UUID correlationId = reader.readBoolean() ? reader.readUuid() : null;
		UUID receiverId = reader.readUuid();
		MessengerResultType resultType = MessengerResultType.byName(reader.readUtf());
		String miniMessage = reader.readUtf();
		Map<String, String> metadata = MessengerCodec.readStringMap(reader);
		return new MessengerResultMessage(protocolVersion, correlationId, receiverId, resultType, miniMessage, metadata);
	}
}
