package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public final class MessengerPresenceMessage {
	private final int protocolVersion;
	private final MessengerPresenceType presenceType;
	private final UUID playerId;
	private final String playerName;
	private final String fromServer;
	private final String toServer;
	private final boolean firstJoin;

	public MessengerPresenceMessage(int protocolVersion, MessengerPresenceType presenceType, UUID playerId, String playerName, String fromServer, String toServer, boolean firstJoin) {
		Objects.requireNonNull(presenceType, "presenceType");
		Objects.requireNonNull(playerId, "playerId");
		playerName = playerName == null ? "" : playerName;
		fromServer = fromServer == null ? "" : fromServer;
		toServer = toServer == null ? "" : toServer;

		this.protocolVersion = protocolVersion;
		this.presenceType = presenceType;
		this.playerId = playerId;
		this.playerName = playerName;
		this.fromServer = fromServer;
		this.toServer = toServer;
		this.firstJoin = firstJoin;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public MessengerPresenceType presenceType() {
		return presenceType;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public String fromServer() {
		return fromServer;
	}

	public String toServer() {
		return toServer;
	}

	public boolean firstJoin() {
		return firstJoin;
	}

	public static final int CURRENT_PROTOCOL = 1;


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
