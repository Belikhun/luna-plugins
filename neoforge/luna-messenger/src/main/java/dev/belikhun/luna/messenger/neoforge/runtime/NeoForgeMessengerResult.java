package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.messenger.MessengerResultType;

import java.util.Map;
import java.util.UUID;

public record NeoForgeMessengerResult(
	UUID correlationId,
	UUID receiverId,
	MessengerResultType resultType,
	String miniMessage,
	Map<String, String> metadata,
	long receivedAtEpochMillis
) {
}
