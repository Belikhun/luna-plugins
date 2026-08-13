package dev.belikhun.luna.legacy.messenger;

import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public final class MessagingContext {
	private final MessagingContextType type;
	private final UUID directTargetId;
	private final String directTargetName;

	public MessagingContext(MessagingContextType type, UUID directTargetId, String directTargetName) {
		Objects.requireNonNull(type, "type");
		if (type != MessagingContextType.DIRECT && (directTargetId != null || directTargetName != null)) {
			throw new IllegalArgumentException("Ngữ cảnh không phải DIRECT không được có direct target.");
		}
		if (type == MessagingContextType.DIRECT && directTargetId == null) {
			throw new IllegalArgumentException("Ngữ cảnh DIRECT yêu cầu directTargetId.");
		}

		this.type = type;
		this.directTargetId = directTargetId;
		this.directTargetName = directTargetName;
	}

	public MessagingContextType type() {
		return type;
	}

	public UUID directTargetId() {
		return directTargetId;
	}

	public String directTargetName() {
		return directTargetName;
	}


	public static MessagingContext network() {
		return new MessagingContext(MessagingContextType.NETWORK, null, null);
	}

	public static MessagingContext server() {
		return new MessagingContext(MessagingContextType.SERVER, null, null);
	}

	public static MessagingContext direct(UUID targetId, String targetName) {
		return new MessagingContext(MessagingContextType.DIRECT, targetId, targetName);
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeUtf(type.name());
		writer.writeBoolean(directTargetId != null);
		if (directTargetId != null) {
			writer.writeUuid(directTargetId);
		}
		writer.writeBoolean(directTargetName != null && !Strings.isBlank(directTargetName));
		if (directTargetName != null && !Strings.isBlank(directTargetName)) {
			writer.writeUtf(directTargetName);
		}
	}

	public static MessagingContext readFrom(PluginMessageReader reader) {
		MessagingContextType type = MessagingContextType.byName(reader.readUtf());
		UUID targetId = reader.readBoolean() ? reader.readUuid() : null;
		String targetName = reader.readBoolean() ? reader.readUtf() : null;
		return new MessagingContext(type, targetId, targetName);
	}
}
