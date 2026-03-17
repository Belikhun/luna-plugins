package dev.belikhun.luna.core.api.messaging;

public record AmqpPluginMessageEnvelope(
	int protocolVersion,
	String channel,
	String sourceServerName,
	String sourcePlayerId,
	String sourcePlayerName,
	String targetServerName,
	byte[] payload
) {
	public static final int CURRENT_PROTOCOL = 1;

	public byte[] encode() {
		PluginMessageWriter writer = PluginMessageWriter.create();
		writer.writeInt(protocolVersion);
		writer.writeUtf(channel == null ? "" : channel);
		writer.writeUtf(sourceServerName == null ? "" : sourceServerName);
		writer.writeUtf(sourcePlayerId == null ? "" : sourcePlayerId);
		writer.writeUtf(sourcePlayerName == null ? "" : sourcePlayerName);
		writer.writeUtf(targetServerName == null ? "" : targetServerName);
		byte[] safePayload = payload == null ? new byte[0] : payload;
		writer.writeInt(safePayload.length);
		writer.writeBytes(safePayload);
		return writer.toByteArray();
	}

	public static AmqpPluginMessageEnvelope decode(byte[] bytes) {
		PluginMessageReader reader = PluginMessageReader.of(bytes);
		int protocol = reader.readInt();
		String channel = reader.readUtf();
		String sourceServerName = reader.readUtf();
		String sourcePlayerId = reader.readUtf();
		String sourcePlayerName = reader.readUtf();
		String targetServerName = reader.readUtf();
		byte[] payload = reader.readBytes(Math.max(0, reader.readInt()));
		return new AmqpPluginMessageEnvelope(
			protocol,
			channel,
			sourceServerName,
			sourcePlayerId,
			sourcePlayerName,
			targetServerName,
			payload
		);
	}
}
