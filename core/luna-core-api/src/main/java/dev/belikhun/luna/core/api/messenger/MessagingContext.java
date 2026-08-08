package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.Objects;
import java.util.UUID;

public record MessagingContext(
	MessagingContextType type,
	UUID directTargetId,
	String directTargetName
) {
	public MessagingContext {
		Objects.requireNonNull(type, "type");
		if (type != MessagingContextType.DIRECT && (directTargetId != null || directTargetName != null)) {
			throw new IllegalArgumentException("Ngữ cảnh không phải DIRECT không được có direct target.");
		}
		if (type == MessagingContextType.DIRECT && directTargetId == null) {
			throw new IllegalArgumentException("Ngữ cảnh DIRECT yêu cầu directTargetId.");
		}
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
		writer.writeBoolean(directTargetName != null && !directTargetName.isBlank());
		if (directTargetName != null && !directTargetName.isBlank()) {
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
