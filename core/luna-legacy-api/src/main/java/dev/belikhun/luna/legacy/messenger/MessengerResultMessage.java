package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class MessengerResultMessage {
	private final int protocolVersion;
	private final UUID correlationId;
	private final UUID receiverId;
	private final MessengerResultType resultType;
	private final String miniMessage;
	private final Map<String, String> metadata;

	public MessengerResultMessage(int protocolVersion, UUID correlationId, UUID receiverId, MessengerResultType resultType, String miniMessage, Map<String, String> metadata) {
		Objects.requireNonNull(receiverId, "receiverId");
		Objects.requireNonNull(resultType, "resultType");
		miniMessage = miniMessage == null ? "" : miniMessage;
		metadata = metadata == null
			? Collections.<String, String>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));

		this.protocolVersion = protocolVersion;
		this.correlationId = correlationId;
		this.receiverId = receiverId;
		this.resultType = resultType;
		this.miniMessage = miniMessage;
		this.metadata = metadata;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public UUID correlationId() {
		return correlationId;
	}

	public UUID receiverId() {
		return receiverId;
	}

	public MessengerResultType resultType() {
		return resultType;
	}

	public String miniMessage() {
		return miniMessage;
	}

	public Map<String, String> metadata() {
		return metadata;
	}

	public static final int CURRENT_PROTOCOL = 1;


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
