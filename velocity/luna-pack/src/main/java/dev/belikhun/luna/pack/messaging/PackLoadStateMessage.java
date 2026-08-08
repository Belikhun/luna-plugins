package dev.belikhun.luna.pack.messaging;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Locale;
import java.util.UUID;

public record PackLoadStateMessage(
	UUID playerId,
	String playerName,
	PackLoadState state
) {
	public void writeTo(PluginMessageWriter writer) {
		writer
			.writeUuid(playerId)
			.writeUtf(playerName)
			.writeUtf(state.name());
	}

	public static PackLoadStateMessage readFrom(PluginMessageReader reader) {
		UUID playerId = reader.readUuid();
		String playerName = reader.readUtf();
		PackLoadState state = PackLoadState.valueOf(reader.readUtf().toUpperCase(Locale.ROOT));
		return new PackLoadStateMessage(playerId, playerName, state);
	}

	public static PackLoadStateMessage started(UUID playerId, String playerName) {
		return new PackLoadStateMessage(playerId, playerName, PackLoadState.STARTED);
	}

	public static PackLoadStateMessage completed(UUID playerId, String playerName) {
		return new PackLoadStateMessage(playerId, playerName, PackLoadState.COMPLETED);
	}
}
