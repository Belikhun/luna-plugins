package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.legacy.messenger.MessengerChannels;
import dev.belikhun.luna.legacy.messenger.MessengerCommandRequest;
import dev.belikhun.luna.legacy.messenger.MessengerCommandType;
import dev.belikhun.luna.legacy.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.legacy.messenger.MessengerPresenceType;
import dev.belikhun.luna.legacy.messenger.MessengerResultMessage;
import dev.belikhun.luna.legacy.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.legacy.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.legacy.string.Formatters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The messenger as this backend sees it: who is online anywhere on the network,
 * what this backend has asked the proxy for, and what came back.
 *
 * The proxy owns the conversation; a backend only forwards what a player typed
 * and renders the answer. Presence is mirrored here purely so tab-completion for
 * {@code /msg} can offer players who are on other servers.
 */
final class PresenceTrackingMessengerRuntime<P> implements MessengerRuntime<P> {
	/**
	 * A control command repeated inside this window is the client double-firing,
	 * not the player: switching channel twice looks like it did nothing.
	 */
	private static final long CONTROL_COMMAND_DEDUP_WINDOW_MS = 250L;

	private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;
	private static final long TIMEOUT_SWEEP_INTERVAL_MILLIS = 2_000L;
	private static final int MAX_DIRECT_TARGET_SUGGESTIONS = 20;

	private final LunaLogger logger;
	private final PlayerBridge<P> players;
	private final PlayerAudience<P> audience;
	private final PluginMessageBus<P, P> bus;
	private final BackendPlaceholderResolver placeholderResolver;
	private final BackendIdentity backendIdentity;
	private final Map<UUID, String> networkPlayerNames;
	private final Map<UUID, PendingRequest> pendingRequests;
	private final Map<UUID, MessengerResult> latestResults;
	private final Map<UUID, RecentControlCommand> recentControlCommands;
	private final ScheduledExecutorService timeoutExecutor;

	PresenceTrackingMessengerRuntime(
		LunaLogger logger,
		PlayerBridge<P> players,
		PlayerAudience<P> audience,
		PluginMessageBus<P, P> bus,
		BackendPlaceholderResolver placeholderResolver,
		BackendIdentity backendIdentity
	) {
		this.logger = logger.scope("Presence");

		// the seams are handed in rather than read off a core singleton: each
		// platform publishes its own, under its own player type
		this.players = players;
		this.audience = audience;
		this.bus = bus;
		this.placeholderResolver = placeholderResolver;
		this.backendIdentity = backendIdentity;
		this.networkPlayerNames = new ConcurrentHashMap<>();
		this.pendingRequests = new ConcurrentHashMap<>();
		this.latestResults = new ConcurrentHashMap<>();
		this.recentControlCommands = new ConcurrentHashMap<>();
		this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-messenger-fabric-timeouts");
			thread.setDaemon(true);
			return thread;
		});

		this.timeoutExecutor.scheduleAtFixedRate(
			this::sweepTimeouts,
			TIMEOUT_SWEEP_INTERVAL_MILLIS,
			TIMEOUT_SWEEP_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);

		this.bus.registerOutgoing(MessengerChannels.COMMAND);
		this.bus.registerOutgoing(MessengerChannels.PRESENCE);

		this.bus.registerIncoming(MessengerChannels.RESULT, context -> {
			handleResult(MessengerResultMessage.readFrom(context.reader()));
			return PluginMessageDispatchResult.HANDLED;
		});

		this.bus.registerIncoming(MessengerChannels.PRESENCE, context -> {
			handlePresence(MessengerPresenceMessage.readFrom(context.reader()));
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	@Override
	public void publishJoin(P player, boolean firstJoin) {
		if (player == null) {
			return;
		}

		networkPlayerNames.put(players.idOf(player), players.nameOf(player));
		publishPresence(player, MessengerPresenceType.JOIN, firstJoin);
	}

	@Override
	public void publishLeave(P player) {
		if (player == null) {
			return;
		}

		networkPlayerNames.remove(players.idOf(player));
		publishPresence(player, MessengerPresenceType.LEAVE, false);
	}

	@Override
	public boolean sendCommand(P player, MessengerCommandType commandType, String argument) {
		return sendCommand(player, commandType, argument, null);
	}

	@Override
	public boolean sendCommand(P player, MessengerCommandType commandType, String argument, String targetName) {
		if (player == null || commandType == null) {
			return false;
		}

		if (isDuplicateControlCommand(players.idOf(player), commandType, argument, targetName)) {
			return false;
		}

		UUID requestId = UUID.randomUUID();
		String playerName = players.nameOf(player);
		PlaceholderResolutionResult resolution = placeholderResolver.resolve(new PlaceholderResolutionRequest(
			players.idOf(player),
			playerName,
			localServerName(),
			argument,
			internalValues(playerName, commandType, argument, targetName)
		));

		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			players.idOf(player),
			playerName,
			localServerName(),
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);

		if (!bus.send(player, MessengerChannels.COMMAND, request::writeTo)) {
			return false;
		}

		pendingRequests.put(requestId, new PendingRequest(players.idOf(player), commandType, System.currentTimeMillis()));
		logger.audit("Đã gửi command " + commandType.name() + " reqId=" + requestId + " cho " + playerName);

		return true;
	}

	@Override
	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String currentSender = senderName == null ? "" : senderName;
		LinkedHashSet<String> matches = new LinkedHashSet<>();

		for (String name : networkPlayerNames.values()) {
			if (Strings.isBlank(name) || name.equalsIgnoreCase(currentSender)) {
				continue;
			}

			if (!token.isEmpty() && !name.regionMatches(true, 0, token, 0, token.length())) {
				continue;
			}

			matches.add(name);
		}

		List<String> sorted = new ArrayList<>(matches);
		sorted.sort(String::compareTo);

		return sorted.size() > MAX_DIRECT_TARGET_SUGGESTIONS
			? Collections.unmodifiableList(new ArrayList<String>(sorted.subList(0, MAX_DIRECT_TARGET_SUGGESTIONS)))
			: Collections.unmodifiableList(new ArrayList<String>(sorted));
	}

	@Override
	public Optional<MessengerResult> latestResult(UUID playerId) {
		if (playerId == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(latestResults.get(playerId));
	}

	@Override
	public void close() {
		timeoutExecutor.shutdownNow();
		bus.unregisterIncoming(MessengerChannels.RESULT);
		bus.unregisterIncoming(MessengerChannels.PRESENCE);
		bus.unregisterOutgoing(MessengerChannels.COMMAND);
		bus.unregisterOutgoing(MessengerChannels.PRESENCE);
		networkPlayerNames.clear();
		pendingRequests.clear();
		latestResults.clear();
		recentControlCommands.clear();
	}

	private void publishPresence(P player, MessengerPresenceType presenceType, boolean firstJoin) {
		MessengerPresenceMessage presence = new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			presenceType,
			players.idOf(player),
			players.nameOf(player),
			"",
			"",
			firstJoin
		);

		bus.send(player, MessengerChannels.PRESENCE, presence::writeTo);
	}

	/** The proxy's name for this server, read per use: it is not known at boot. */
	private String localServerName() {
		return backendIdentity.nameOr("backend");
	}

	private Map<String, String> internalValues(String playerName, MessengerCommandType commandType, String argument, String targetName) {
		Map<String, String> values = new LinkedHashMap<>();

		values.put("sender_name", playerName);
		values.put("player_name", playerName);
		values.put("server_name", localServerName());
		values.put("sender_server", localServerName());

		if (commandType == MessengerCommandType.SWITCH_DIRECT
			|| commandType == MessengerCommandType.SEND_DIRECT
			|| commandType == MessengerCommandType.SEND_POKE) {
			String directTarget = targetName != null ? targetName : argument;
			values.put("target_name", directTarget == null ? "" : directTarget);
		}

		return values;
	}

	private void handlePresence(MessengerPresenceMessage presence) {
		if (presence == null) {
			return;
		}

		if (presence.presenceType() == MessengerPresenceType.LEAVE) {
			networkPlayerNames.remove(presence.playerId());
			return;
		}

		networkPlayerNames.put(presence.playerId(), presence.playerName());
		logger.debug("Đã cập nhật presence cho " + presence.playerName() + " type=" + presence.presenceType().name());
	}

	private void handleResult(MessengerResultMessage result) {
		if (result == null) {
			return;
		}

		MessengerResult runtimeResult = new MessengerResult(
			result.correlationId(),
			result.receiverId(),
			result.resultType(),
			result.miniMessage(),
			result.metadata(),
			System.currentTimeMillis()
		);

		latestResults.put(result.receiverId(), runtimeResult);

		if (result.correlationId() != null) {
			pendingRequests.remove(result.correlationId());
		}

		deliverResult(runtimeResult);
		logger.audit("Đã nhận messenger result=" + result.resultType().name() + " correlationId=" + result.correlationId());
	}

	private void deliverResult(MessengerResult result) {
		P receiver = players.byId(result.receiverId());

		if (receiver == null) {
			return;
		}

		// a line that is nothing but formatting has no content to show; the proxy
		// sends those when a result is meant to update state rather than be read
		if (Strings.isBlank(Formatters.stripFormats(result.miniMessage()))) {
			return;
		}

		audience.sendMini(receiver, result.miniMessage());
	}

	private boolean isDuplicateControlCommand(UUID playerId, MessengerCommandType commandType, String argument, String targetName) {
		if (commandType != MessengerCommandType.SWITCH_NETWORK
			&& commandType != MessengerCommandType.SWITCH_SERVER
			&& commandType != MessengerCommandType.SWITCH_DIRECT) {
			return false;
		}

		String fingerprint = commandType.name()
			+ "|" + (argument == null ? "" : argument)
			+ "|" + (targetName == null ? "" : targetName);
		long now = System.currentTimeMillis();
		RecentControlCommand previous = recentControlCommands.get(playerId);

		if (previous != null && previous.fingerprint().equals(fingerprint) && now - previous.atMillis() < CONTROL_COMMAND_DEDUP_WINDOW_MS) {
			return true;
		}

		recentControlCommands.put(playerId, new RecentControlCommand(fingerprint, now));

		return false;
	}

	private void sweepTimeouts() {
		long now = System.currentTimeMillis();

		for (Map.Entry<UUID, PendingRequest> entry : pendingRequests.entrySet()) {
			PendingRequest pending = entry.getValue();

			if (now - pending.createdAtEpochMillis() < REQUEST_TIMEOUT_MILLIS) {
				continue;
			}

			if (!pendingRequests.remove(entry.getKey(), pending)) {
				continue;
			}

			notifyTimeout(pending);
			logger.warn("Timeout command=" + pending.commandType().name() + " reqId=" + entry.getKey());
		}
	}

	private void notifyTimeout(PendingRequest pending) {
		final P player = players.byId(pending.playerId());

		if (player == null) {
			return;
		}

		// plain, not MiniMessage: this text is luna's own and carries no markup,
		// and parsing it would make a stray '<' in a future wording an injection
		final String message = timeoutMessage(pending.commandType());

		players.onServerThread(() -> audience.sendPlain(player, message));
	}

	private String timeoutMessage(MessengerCommandType commandType) {
		switch (commandType) {
			case SEND_POKE:
				return "❌ Yêu cầu chọc đã hết thời gian chờ.";

			case SEND_DIRECT:
				return "❌ Tin nhắn riêng đã hết thời gian chờ.";

			case SEND_REPLY:
				return "❌ Tin nhắn trả lời đã hết thời gian chờ.";

			case SEND_CHAT:
				return "❌ Tin nhắn chat đã hết thời gian chờ.";

			// a classic switch cannot be exhaustive over an enum the way the switch
			// expression this replaces was, so the control commands share the default
			// rather than being listed and leaving the compiler to demand one anyway
			default:
				return "❌ Không thể cập nhật kênh nhắn tin lúc này.";
		}
	}

	private static final class PendingRequest {
		private final UUID playerId;
		private final MessengerCommandType commandType;
		private final long createdAtEpochMillis;

		private PendingRequest(UUID playerId, MessengerCommandType commandType, long createdAtEpochMillis) {
			this.playerId = playerId;
			this.commandType = commandType;
			this.createdAtEpochMillis = createdAtEpochMillis;
		}

		private UUID playerId() {
			return playerId;
		}

		private MessengerCommandType commandType() {
			return commandType;
		}

		private long createdAtEpochMillis() {
			return createdAtEpochMillis;
		}
	}

	private static final class RecentControlCommand {
		private final String fingerprint;
		private final long atMillis;

		private RecentControlCommand(String fingerprint, long atMillis) {
			this.fingerprint = fingerprint;
			this.atMillis = atMillis;
		}

		private String fingerprint() {
			return fingerprint;
		}

		private long atMillis() {
			return atMillis;
		}
	}
}
