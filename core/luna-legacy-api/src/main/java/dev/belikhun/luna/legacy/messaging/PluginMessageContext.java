package dev.belikhun.luna.legacy.messaging;

public final class PluginMessageContext<SOURCE> {
	private final PluginMessageChannel channel;
	private final SOURCE source;
	private final byte[] payload;

	public PluginMessageContext(PluginMessageChannel channel, SOURCE source, byte[] payload) {
		this.channel = channel;
		this.source = source;
		this.payload = payload;
	}

	public PluginMessageChannel channel() {
		return channel;
	}

	public SOURCE source() {
		return source;
	}

	public byte[] payload() {
		return payload;
	}
	public PluginMessageReader reader() {
		return PluginMessageReader.of(payload);
	}
}
