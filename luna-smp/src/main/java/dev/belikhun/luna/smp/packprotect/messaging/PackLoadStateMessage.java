package dev.belikhun.luna.smp.packprotect.messaging;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;

import java.util.Locale;
import java.util.UUID;

public record PackLoadStateMessage(
	UUID playerId,
	String playerName,
	PackLoadState state
) {
	public static PackLoadStateMessage readFrom(PluginMessageReader reader) {
		UUID playerId = reader.readUuid();
		String playerName = reader.readUtf();
		PackLoadState state = PackLoadState.valueOf(reader.readUtf().toUpperCase(Locale.ROOT));
		return new PackLoadStateMessage(playerId, playerName, state);
	}
}
