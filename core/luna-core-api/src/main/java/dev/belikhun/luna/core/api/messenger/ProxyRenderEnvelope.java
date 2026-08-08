package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ProxyRenderEnvelope(
	int protocolVersion,
	UUID senderId,
	String senderName,
	String sourceServer,
	MessagingContext context,
	String rawContent,
	Map<String, String> resolvedValues
) {
	public static final int CURRENT_PROTOCOL = 1;

	public ProxyRenderEnvelope {
		Objects.requireNonNull(senderId, "senderId");
		senderName = senderName == null ? "" : senderName;
		sourceServer = sourceServer == null ? "" : sourceServer;
		rawContent = rawContent == null ? "" : rawContent;
		resolvedValues = resolvedValues == null ? Map.of() : Map.copyOf(resolvedValues);
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeUuid(senderId)
			.writeUtf(senderName)
			.writeUtf(sourceServer)
			.writeBoolean(context != null);
		if (context != null) {
			context.writeTo(writer);
		}
		writer.writeUtf(rawContent);
		MessengerCodec.writeStringMap(writer, resolvedValues);
	}

	public static ProxyRenderEnvelope readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		UUID senderId = reader.readUuid();
		String senderName = reader.readUtf();
		String sourceServer = reader.readUtf();
		MessagingContext context = reader.readBoolean() ? MessagingContext.readFrom(reader) : null;
		String rawContent = reader.readUtf();
		Map<String, String> resolvedValues = MessengerCodec.readStringMap(reader);
		return new ProxyRenderEnvelope(protocolVersion, senderId, senderName, sourceServer, context, rawContent, resolvedValues);
	}
}
