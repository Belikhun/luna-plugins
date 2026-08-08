package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public record MessengerPresenceMessage(
	int protocolVersion,
	MessengerPresenceType presenceType,
	UUID playerId,
	String playerName,
	String fromServer,
	String toServer,
	boolean firstJoin
) {
	public static final int CURRENT_PROTOCOL = 1;

	public MessengerPresenceMessage {
		Objects.requireNonNull(presenceType, "presenceType");
		Objects.requireNonNull(playerId, "playerId");
		playerName = playerName == null ? "" : playerName;
		fromServer = fromServer == null ? "" : fromServer;
		toServer = toServer == null ? "" : toServer;
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeUtf(presenceType.name())
			.writeUuid(playerId)
			.writeUtf(playerName)
			.writeUtf(fromServer)
			.writeUtf(toServer)
			.writeBoolean(firstJoin);
	}

	public static MessengerPresenceMessage readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		MessengerPresenceType presenceType = MessengerPresenceType.byName(reader.readUtf());
		UUID playerId = reader.readUuid();
		String playerName = reader.readUtf();
		String fromServer = reader.readUtf();
		String toServer = reader.readUtf();
		boolean firstJoin = reader.readBoolean();
		return new MessengerPresenceMessage(protocolVersion, presenceType, playerId, playerName, fromServer, toServer, firstJoin);
	}
}
