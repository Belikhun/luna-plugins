package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MessengerCommandRequest(
	int protocolVersion,
	UUID requestId,
	MessengerCommandType commandType,
	UUID senderId,
	String senderName,
	String senderServer,
	String argument,
	MessagingContext contextHint,
	Map<String, String> resolvedValues
) {
	public static final int CURRENT_PROTOCOL = 1;

	public MessengerCommandRequest {
		Objects.requireNonNull(requestId, "requestId");
		Objects.requireNonNull(commandType, "commandType");
		Objects.requireNonNull(senderId, "senderId");
		senderName = senderName == null ? "" : senderName;
		senderServer = senderServer == null ? "" : senderServer;
		argument = argument == null ? "" : argument;
		resolvedValues = resolvedValues == null ? Map.of() : Map.copyOf(resolvedValues);
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeUuid(requestId)
			.writeUtf(commandType.name())
			.writeUuid(senderId)
			.writeUtf(senderName)
			.writeUtf(senderServer)
			.writeUtf(argument)
			.writeBoolean(contextHint != null);
		if (contextHint != null) {
			contextHint.writeTo(writer);
		}
		MessengerCodec.writeStringMap(writer, resolvedValues);
	}

	public static MessengerCommandRequest readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		UUID requestId = reader.readUuid();
		MessengerCommandType commandType = MessengerCommandType.byName(reader.readUtf());
		UUID senderId = reader.readUuid();
		String senderName = reader.readUtf();
		String senderServer = reader.readUtf();
		String argument = reader.readUtf();
		MessagingContext contextHint = reader.readBoolean() ? MessagingContext.readFrom(reader) : null;
		Map<String, String> resolvedValues = MessengerCodec.readStringMap(reader);
		return new MessengerCommandRequest(protocolVersion, requestId, commandType, senderId, senderName, senderServer, argument, contextHint, resolvedValues);
	}
}
