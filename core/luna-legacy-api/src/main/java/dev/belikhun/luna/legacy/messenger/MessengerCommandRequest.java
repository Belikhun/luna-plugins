package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class MessengerCommandRequest {
	private final int protocolVersion;
	private final UUID requestId;
	private final MessengerCommandType commandType;
	private final UUID senderId;
	private final String senderName;
	private final String senderServer;
	private final String argument;
	private final MessagingContext contextHint;
	private final Map<String, String> resolvedValues;

	public MessengerCommandRequest(int protocolVersion, UUID requestId, MessengerCommandType commandType, UUID senderId, String senderName, String senderServer, String argument, MessagingContext contextHint, Map<String, String> resolvedValues) {
		Objects.requireNonNull(requestId, "requestId");
		Objects.requireNonNull(commandType, "commandType");
		Objects.requireNonNull(senderId, "senderId");
		senderName = senderName == null ? "" : senderName;
		senderServer = senderServer == null ? "" : senderServer;
		argument = argument == null ? "" : argument;
		resolvedValues = resolvedValues == null
			? Collections.<String, String>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<String, String>(resolvedValues));

		this.protocolVersion = protocolVersion;
		this.requestId = requestId;
		this.commandType = commandType;
		this.senderId = senderId;
		this.senderName = senderName;
		this.senderServer = senderServer;
		this.argument = argument;
		this.contextHint = contextHint;
		this.resolvedValues = resolvedValues;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public UUID requestId() {
		return requestId;
	}

	public MessengerCommandType commandType() {
		return commandType;
	}

	public UUID senderId() {
		return senderId;
	}

	public String senderName() {
		return senderName;
	}

	public String senderServer() {
		return senderServer;
	}

	public String argument() {
		return argument;
	}

	public MessagingContext contextHint() {
		return contextHint;
	}

	public Map<String, String> resolvedValues() {
		return resolvedValues;
	}

	public static final int CURRENT_PROTOCOL = 1;


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
