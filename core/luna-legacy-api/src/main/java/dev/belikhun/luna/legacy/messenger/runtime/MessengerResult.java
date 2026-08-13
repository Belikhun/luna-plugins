package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.messenger.MessengerResultType;

import java.util.Map;
import java.util.UUID;

/** One answer the proxy sent back, as this backend recorded it. */
public final class MessengerResult {
	private final UUID correlationId;
	private final UUID receiverId;
	private final MessengerResultType resultType;
	private final String miniMessage;
	private final Map<String, String> metadata;
	private final long receivedAtEpochMillis;

	public MessengerResult(UUID correlationId, UUID receiverId, MessengerResultType resultType, String miniMessage, Map<String, String> metadata, long receivedAtEpochMillis) {
		this.correlationId = correlationId;
		this.receiverId = receiverId;
		this.resultType = resultType;
		this.miniMessage = miniMessage;
		this.metadata = metadata;
		this.receivedAtEpochMillis = receivedAtEpochMillis;
	}

	public UUID correlationId() {
		return correlationId;
	}

	public UUID receiverId() {
		return receiverId;
	}

	public MessengerResultType resultType() {
		return resultType;
	}

	public String miniMessage() {
		return miniMessage;
	}

	public Map<String, String> metadata() {
		return metadata;
	}

	public long receivedAtEpochMillis() {
		return receivedAtEpochMillis;
	}

}
