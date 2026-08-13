package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

public final class ProxyRenderEnvelope {
	private final int protocolVersion;
	private final UUID senderId;
	private final String senderName;
	private final String sourceServer;
	private final MessagingContext context;
	private final String rawContent;
	private final Map<String, String> resolvedValues;

	public ProxyRenderEnvelope(int protocolVersion, UUID senderId, String senderName, String sourceServer, MessagingContext context, String rawContent, Map<String, String> resolvedValues) {
		Objects.requireNonNull(senderId, "senderId");
		senderName = senderName == null ? "" : senderName;
		sourceServer = sourceServer == null ? "" : sourceServer;
		rawContent = rawContent == null ? "" : rawContent;
		resolvedValues = resolvedValues == null
			? Collections.<String, String>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<String, String>(resolvedValues));

		this.protocolVersion = protocolVersion;
		this.senderId = senderId;
		this.senderName = senderName;
		this.sourceServer = sourceServer;
		this.context = context;
		this.rawContent = rawContent;
		this.resolvedValues = resolvedValues;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public UUID senderId() {
		return senderId;
	}

	public String senderName() {
		return senderName;
	}

	public String sourceServer() {
		return sourceServer;
	}

	public MessagingContext context() {
		return context;
	}

	public String rawContent() {
		return rawContent;
	}

	public Map<String, String> resolvedValues() {
		return resolvedValues;
	}

	public static final int CURRENT_PROTOCOL = 1;


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
