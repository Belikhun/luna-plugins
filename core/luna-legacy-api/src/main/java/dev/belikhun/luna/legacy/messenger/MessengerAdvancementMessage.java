package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

/**
 * An advancement a player earned, on its way to the proxy.
 *
 * Java 8 twin of the modern trunk's record; the wire format is identical field
 * for field, because a 1.12.2 backend and a modern backend publish onto the same
 * channel and the proxy decodes both with one reader.
 *
 * The title and description travel as **resolved text**. On 1.12.2 that matters
 * even more than on modern: the era's advancement titles are `TextComponentTranslation`
 * whose keys live in the server jar and in each mod's `assets/<mod>/lang/*.lang`,
 * none of which the proxy can see.
 */
public final class MessengerAdvancementMessage {
	public static final int CURRENT_PROTOCOL = 1;

	private final int protocolVersion;
	private final UUID playerId;
	private final String playerName;
	private final String server;
	private final String advancementId;
	private final String title;
	private final String description;
	private final MessengerAdvancementFrame frame;

	public MessengerAdvancementMessage(
		int protocolVersion,
		UUID playerId,
		String playerName,
		String server,
		String advancementId,
		String title,
		String description,
		MessengerAdvancementFrame frame
	) {
		Objects.requireNonNull(playerId, "playerId");

		this.protocolVersion = protocolVersion;
		this.playerId = playerId;
		this.playerName = playerName == null ? "" : playerName;
		this.server = server == null ? "" : server;
		this.advancementId = advancementId == null ? "" : advancementId;
		this.title = title == null ? "" : title;
		this.description = description == null ? "" : description;
		this.frame = frame == null ? MessengerAdvancementFrame.TASK : frame;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public String server() {
		return server;
	}

	public String advancementId() {
		return advancementId;
	}

	public String title() {
		return title;
	}

	public String description() {
		return description;
	}

	public MessengerAdvancementFrame frame() {
		return frame;
	}

	/**
	 * Write this message onto the plugin-message bus.
	 *
	 * @param writer the writer to append to
	 */
	public void writeTo(PluginMessageWriter writer) {
		writer.writeInt(protocolVersion)
			.writeUuid(playerId)
			.writeUtf(playerName)
			.writeUtf(server)
			.writeUtf(advancementId)
			.writeUtf(title)
			.writeUtf(description)
			.writeUtf(frame.name());
	}

	/**
	 * Read a message written by {@link #writeTo(PluginMessageWriter)}.
	 *
	 * @param reader the reader positioned at the start of the message
	 * @return the decoded message
	 */
	public static MessengerAdvancementMessage readFrom(PluginMessageReader reader) {
		int protocolVersion = reader.readInt();
		UUID playerId = reader.readUuid();
		String playerName = reader.readUtf();
		String server = reader.readUtf();
		String advancementId = reader.readUtf();
		String title = reader.readUtf();
		String description = reader.readUtf();
		MessengerAdvancementFrame frame = MessengerAdvancementFrame.byName(reader.readUtf());

		return new MessengerAdvancementMessage(
			protocolVersion,
			playerId,
			playerName,
			server,
			advancementId,
			title,
			description,
			frame
		);
	}
}
