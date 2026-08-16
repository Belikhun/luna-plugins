package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

/**
 * An advancement a player earned, on its way to the proxy.
 *
 * **The title and description travel as resolved text, not as translation
 * keys.** Only the backend can resolve them: vanilla's own keys live in the
 * server jar, a mod's live in that mod's assets, and a data pack's title is
 * usually literal text with no key at all. The proxy has none of those, so
 * resolving there would print `advancements.story.mine_stone.title` for vanilla
 * and mojibake for everything else. Resolving at the source is also what makes
 * modded and data-pack advancements work without the proxy knowing they exist.
 *
 * `advancementId` is still carried so the proxy can filter or route on it
 * (`minecraft:story/mine_stone`, `mymod:machines/first_press`) without having to
 * parse display text.
 */
public record MessengerAdvancementMessage(
	int protocolVersion,
	UUID playerId,
	String playerName,
	String server,
	String advancementId,
	String title,
	String description,
	MessengerAdvancementFrame frame
) {
	public static final int CURRENT_PROTOCOL = 1;

	public MessengerAdvancementMessage {
		Objects.requireNonNull(playerId, "playerId");

		playerName = playerName == null ? "" : playerName;
		server = server == null ? "" : server;
		advancementId = advancementId == null ? "" : advancementId;
		title = title == null ? "" : title;
		description = description == null ? "" : description;
		frame = frame == null ? MessengerAdvancementFrame.TASK : frame;
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
