package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

public final class ServerSelectorOpenPayloadWriter {
	private ServerSelectorOpenPayloadWriter() {
	}

	public static void write(PluginMessageWriter writer, VelocityServerSelectorConfig config) {
		writer.writeUtf("open-v2");
		writer.writeUtf(config.template().name());
		writer.writeUtf(config.template().header());
		writer.writeUtf(config.template().bodyLine());
		writer.writeUtf(config.template().footer());

		writer.writeInt(config.servers().size());
		for (VelocityServerSelectorConfig.ServerDefinition server : config.servers().values()) {
			writer.writeUtf(server.backendName());
			writer.writeUtf(server.displayName());
			writer.writeUtf(server.accentColor());
			writer.writeUtf(server.permission());
			writer.writeInt(server.description().size());
			for (String line : server.description()) {
				writer.writeUtf(line == null ? "" : line);
			}
		}
	}
}
