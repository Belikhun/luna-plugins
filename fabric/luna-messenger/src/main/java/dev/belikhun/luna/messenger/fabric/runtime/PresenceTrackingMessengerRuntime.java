package dev.belikhun.luna.messenger.fabric.runtime;

import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.text.FabricTextComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
final class PresenceTrackingMessengerRuntime implements FabricMessengerRuntime {
	/**
	 * A control command repeated inside this window is the client double-firing,
	 * not the player: switching channel twice looks like it did nothing.
	 */
	private static final long CONTROL_COMMAND_DEDUP_WINDOW_MS = 250L;

	private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;
	private static final long TIMEOUT_SWEEP_INTERVAL_MILLIS = 2_000L;
	private static final int MAX_DIRECT_TARGET_SUGGESTIONS = 20;

	private final LunaLogger logger;
	private final PluginMessageBus<ServerPlayer, ServerPlayer> bus;
	private final BackendPlaceholderResolver placeholderResolver;
	private final BackendIdentity backendIdentity;
	private final Map<UUID, String> networkPlayerNames;
	private final Map<UUID, PendingRequest> pendingRequests;
	private final Map<UUID, MessengerResult> latestResults;
	private final Map<UUID, RecentControlCommand> recentControlCommands;
	private final ScheduledExecutorService timeoutExecutor;

	PresenceTrackingMessengerRuntime(
		LunaLogger logger,
		PluginMessageBus<ServerPlayer, ServerPlayer> bus,
		BackendPlaceholderResolver placeholderResolver,
		BackendIdentity backendIdentity
	) {
		this.logger = logger.scope("Presence");
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
	public void publishJoin(ServerPlayer player, boolean firstJoin) {
		if (player == null) {
			return;
		}

		networkPlayerNames.put(player.getUUID(), player.getScoreboardName());
		publishPresence(player, MessengerPresenceType.JOIN, firstJoin);
	}

	@Override
	public void publishLeave(ServerPlayer player) {
		if (player == null) {
			return;
		}

		networkPlayerNames.remove(player.getUUID());
		publishPresence(player, MessengerPresenceType.LEAVE, false);
	}

	@Override
	public boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument) {
		return sendCommand(player, commandType, argument, null);
	}

	@Override
	public boolean sendCommand(ServerPlayer player, MessengerCommandType commandType, String argument, String targetName) {
		if (player == null || commandType == null) {
			return false;
		}

		if (isDuplicateControlCommand(player.getUUID(), commandType, argument, targetName)) {
			return false;
		}

		UUID requestId = UUID.randomUUID();
		String playerName = player.getScoreboardName();
		PlaceholderResolutionResult resolution = placeholderResolver.resolve(new PlaceholderResolutionRequest(
			player.getUUID(),
			playerName,
			localServerName(),
			argument,
			internalValues(playerName, commandType, argument, targetName)
		));

		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			player.getUUID(),
			playerName,
			localServerName(),
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);

		if (!bus.send(player, MessengerChannels.COMMAND, request::writeTo)) {
			return false;
		}

		pendingRequests.put(requestId, new PendingRequest(player.getUUID(), commandType, System.currentTimeMillis()));
		logger.audit("Đã gửi command " + commandType.name() + " reqId=" + requestId + " cho " + playerName);

		return true;
	}

	@Override
	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String currentSender = senderName == null ? "" : senderName;
		LinkedHashSet<String> matches = new LinkedHashSet<>();

		for (String name : networkPlayerNames.values()) {
			if (name == null || name.isBlank() || name.equalsIgnoreCase(currentSender)) {
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
			? List.copyOf(sorted.subList(0, MAX_DIRECT_TARGET_SUGGESTIONS))
			: List.copyOf(sorted);
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

	private void publishPresence(ServerPlayer player, MessengerPresenceType presenceType, boolean firstJoin) {
		MessengerPresenceMessage presence = new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			presenceType,
			player.getUUID(),
			player.getScoreboardName(),
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
		MinecraftServer server = LunaCoreFabric.services().server();
		ServerPlayer receiver = server.getPlayerList().getPlayer(result.receiverId());

		if (receiver == null) {
			return;
		}

		// a line that is nothing but formatting has no content to show; the proxy
		// sends those when a result is meant to update state rather than be read
		if (Formatters.stripFormats(result.miniMessage()).isBlank()) {
			return;
		}

		receiver.sendSystemMessage(FabricTextComponents.mini(result.miniMessage()));
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
		MinecraftServer server = LunaCoreFabric.services().server();
		ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());

		if (player == null) {
			return;
		}

		Component message = Component.literal(timeoutMessage(pending.commandType()));

		server.execute(() -> player.sendSystemMessage(message));
	}

	private String timeoutMessage(MessengerCommandType commandType) {
		return switch (commandType) {
			case SEND_POKE -> "❌ Yêu cầu chọc đã hết thời gian chờ.";
			case SEND_DIRECT -> "❌ Tin nhắn riêng đã hết thời gian chờ.";
			case SEND_REPLY -> "❌ Tin nhắn trả lời đã hết thời gian chờ.";
			case SEND_CHAT -> "❌ Tin nhắn chat đã hết thời gian chờ.";
			case SWITCH_NETWORK, SWITCH_SERVER, SWITCH_DIRECT -> "❌ Không thể cập nhật kênh nhắn tin lúc này.";
		};
	}

	private record PendingRequest(UUID playerId, MessengerCommandType commandType, long createdAtEpochMillis) {
	}

	private record RecentControlCommand(String fingerprint, long atMillis) {
	}
}
