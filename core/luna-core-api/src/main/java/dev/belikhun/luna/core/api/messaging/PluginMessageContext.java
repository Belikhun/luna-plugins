package dev.belikhun.luna.core.api.messaging;

public record PluginMessageContext<SOURCE>(
	PluginMessageChannel channel,
	SOURCE source,
	byte[] payload
) {
	public PluginMessageReader reader() {
		return PluginMessageReader.of(payload);
	}
}
