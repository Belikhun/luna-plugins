package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricMessengerGateway {
	private static final long MIN_TIMEOUT_MILLIS = 1000L;

	private final LunaLogger logger;
	private final PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus;
	private final BackendPlaceholderResolver placeholderResolver;
	private final boolean timeoutEnabled;
	private final long requestTimeoutMillis;
	private final Map<UUID, String> networkPlayerNames = new ConcurrentHashMap<>();
	private final Map<UUID, MessengerResultMessage> latestResultByReceiver = new ConcurrentHashMap<>();
	private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

	public FabricMessengerGateway(
		LunaLogger logger,
		PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus
	) {
		this(logger, bus, new FabricBackendPlaceholderResolver(), 6000L, true);
	}

	public FabricMessengerGateway(
		LunaLogger logger,
		PluginMessageBus<FabricMessageSource, FabricMessageTarget> bus,
		BackendPlaceholderResolver placeholderResolver,
		long requestTimeoutMillis,
		boolean timeoutEnabled
	) {
		this.logger = logger.scope("Gateway");
		this.bus = bus;
		this.placeholderResolver = placeholderResolver;
		this.requestTimeoutMillis = Math.max(MIN_TIMEOUT_MILLIS, requestTimeoutMillis);
		this.timeoutEnabled = timeoutEnabled;
	}

	public void registerChannels() {
		bus.registerOutgoing(MessengerChannels.COMMAND);
		bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			latestResultByReceiver.put(result.receiverId(), result);
			if (result.correlationId() != null) {
				pendingRequests.remove(result.correlationId());
			}
			logger.audit("Đã nhận MessengerResult type=" + result.resultType().name() + " receiver=" + result.receiverId());
			return PluginMessageDispatchResult.HANDLED;
		});
		bus.registerIncoming(MessengerChannels.PRESENCE, context -> {
			MessengerPresenceMessage presence = MessengerPresenceMessage.readFrom(context.reader());
			handlePresence(presence);
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	public void close() {
		bus.unregisterOutgoing(MessengerChannels.COMMAND);
		bus.unregisterIncoming(MessengerChannels.RESULT);
		bus.unregisterIncoming(MessengerChannels.PRESENCE);
		networkPlayerNames.clear();
		latestResultByReceiver.clear();
		pendingRequests.clear();
	}

	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String sender = senderName == null ? "" : senderName;
		ArrayList<String> names = new ArrayList<>();
		for (String value : networkPlayerNames.values()) {
			if (value == null || value.isBlank()) {
				continue;
			}
			if (value.equalsIgnoreCase(sender)) {
				continue;
			}
			if (!token.isEmpty() && !value.regionMatches(true, 0, token, 0, token.length())) {
				continue;
			}
			names.add(value);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		if (names.size() > 20) {
			return List.copyOf(names.subList(0, 20));
		}
		return names;
	}

	public boolean switchNetwork(UUID senderId, String senderName, String senderServer) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_NETWORK, "", Map.of());
	}

	public boolean switchServer(UUID senderId, String senderName, String senderServer) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_SERVER, "", Map.of());
	}

	public boolean switchDirect(UUID senderId, String senderName, String senderServer, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SWITCH_DIRECT, targetName, Map.of());
	}

	public boolean sendDirect(UUID senderId, String senderName, String senderServer, String content, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_DIRECT, content, Map.of("target", normalize(targetName)));
	}

	public boolean sendPoke(UUID senderId, String senderName, String senderServer, String targetName) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_POKE, targetName, Map.of());
	}

	public boolean sendReply(UUID senderId, String senderName, String senderServer, String content) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_REPLY, content, Map.of());
	}

	public boolean sendChat(UUID senderId, String senderName, String senderServer, String content) {
		return send(senderId, senderName, senderServer, MessengerCommandType.SEND_CHAT, content, Map.of());
	}

	public Map<UUID, MessengerResultMessage> latestResults() {
		return Collections.unmodifiableMap(latestResultByReceiver);
	}

	public Map<UUID, PendingRequest> pendingRequests() {
		return Collections.unmodifiableMap(pendingRequests);
	}

	public Map<UUID, String> onlineNetworkPlayers() {
		return Collections.unmodifiableMap(networkPlayerNames);
	}

	public Collection<PendingRequest> collectTimedOutRequests(long currentEpochMillis) {
		if (!timeoutEnabled) {
			return List.of();
		}

		ArrayList<PendingRequest> timedOut = new ArrayList<>();
		for (Map.Entry<UUID, PendingRequest> entry : pendingRequests.entrySet()) {
			PendingRequest pending = entry.getValue();
			if (currentEpochMillis - pending.createdAtEpochMillis() < requestTimeoutMillis) {
				continue;
			}

			if (pendingRequests.remove(entry.getKey(), pending)) {
				timedOut.add(pending);
			}
		}

		return timedOut;
	}

	private boolean send(
		UUID senderId,
		String senderName,
		String senderServer,
		MessengerCommandType commandType,
		String argument,
		Map<String, String> resolvedValues
	) {
		if (senderId == null) {
			return false;
		}
		UUID requestId = UUID.randomUUID();
		Map<String, String> internalValues = new LinkedHashMap<>();
		internalValues.put("sender_name", normalize(senderName));
		internalValues.put("player_name", normalize(senderName));
		internalValues.put("sender_server", normalize(senderServer));
		internalValues.put("server_name", normalize(senderServer));
		if (commandType == MessengerCommandType.SWITCH_DIRECT
			|| commandType == MessengerCommandType.SEND_DIRECT
			|| commandType == MessengerCommandType.SEND_POKE) {
			String targetName = resolvedValues.getOrDefault("target", argument);
			internalValues.put("target_name", normalize(targetName));
		}

		var resolution = placeholderResolver.resolve(new PlaceholderResolutionRequest(
			senderId,
			normalize(senderName),
			normalize(senderServer),
			normalize(argument),
			internalValues
		));

		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			senderId,
			normalize(senderName),
			normalize(senderServer),
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);

		boolean sent = bus.send(FabricMessageTarget.player(senderId), MessengerChannels.COMMAND, request::writeTo);
		if (sent) {
			pendingRequests.put(requestId, new PendingRequest(requestId, senderId, commandType, System.currentTimeMillis()));
		}
		return sent;
	}

	private void handlePresence(MessengerPresenceMessage presence) {
		if (presence.presenceType() == MessengerPresenceType.LEAVE) {
			networkPlayerNames.remove(presence.playerId());
			return;
		}

		networkPlayerNames.put(presence.playerId(), presence.playerName());
	}

	private String normalize(String value) {
		return value == null ? "" : value;
	}

	public record PendingRequest(
		UUID requestId,
		UUID playerId,
		MessengerCommandType commandType,
		long createdAtEpochMillis
	) {
	}
}
