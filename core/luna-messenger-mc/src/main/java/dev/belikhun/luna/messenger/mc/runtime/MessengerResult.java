package dev.belikhun.luna.messenger.mc.runtime;

import dev.belikhun.luna.core.api.messenger.MessengerResultType;

import java.util.Map;
import java.util.UUID;

/** One answer the proxy sent back, as this backend recorded it. */
public record MessengerResult(
	UUID correlationId,
	UUID receiverId,
	MessengerResultType resultType,
	String miniMessage,
	Map<String, String> metadata,
	long receivedAtEpochMillis
) {
}
