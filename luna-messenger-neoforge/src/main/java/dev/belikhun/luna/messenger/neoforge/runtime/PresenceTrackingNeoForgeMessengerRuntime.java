package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.text.NeoForgeTextComponents;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

final class PresenceTrackingNeoForgeMessengerRuntime implements NeoForgeMessengerRuntime {
	private static final long CONTROL_COMMAND_DEDUP_WINDOW_MS = 250L;
	private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;
	private static final long TIMEOUT_SWEEP_INTERVAL_MILLIS = 2_000L;

	private final LunaLogger logger;
	private final NeoForgePluginMessagingBus bus;
	private final BackendPlaceholderResolver placeholderResolver;
	private final String localServerName;
	private final Map<UUID, String> networkPlayerNames;
	private final Map<UUID, PendingRequest> pendingRequests;
	private final Map<UUID, NeoForgeMessengerResult> latestResults;
	private final Map<UUID, RecentControlCommand> recentControlCommands;
	private final ScheduledExecutorService timeoutExecutor;

	PresenceTrackingNeoForgeMessengerRuntime(LunaLogger logger, NeoForgePluginMessagingBus bus, BackendPlaceholderResolver placeholderResolver, String localServerName) {
		this.logger = logger.scope("Presence");
		this.bus = bus;
		this.placeholderResolver = placeholderResolver;
		this.localServerName = localServerName == null || localServerName.isBlank() ? "backend" : localServerName;
		this.networkPlayerNames = new ConcurrentHashMap<>();
		this.pendingRequests = new ConcurrentHashMap<>();
		this.latestResults = new ConcurrentHashMap<>();
		this.recentControlCommands = new ConcurrentHashMap<>();
		this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-messenger-neoforge-timeouts");
			thread.setDaemon(true);
			return thread;
		});
		this.timeoutExecutor.scheduleAtFixedRate(this::sweepTimeouts, TIMEOUT_SWEEP_INTERVAL_MILLIS, TIMEOUT_SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
		this.bus.registerOutgoing(MessengerChannels.COMMAND);
		this.bus.registerOutgoing(MessengerChannels.PRESENCE);
		this.bus.registerIncoming(MessengerChannels.RESULT, context -> {
			MessengerResultMessage result = MessengerResultMessage.readFrom(context.reader());
			handleResult(result);
			return PluginMessageDispatchResult.HANDLED;
		});
		this.bus.registerIncoming(MessengerChannels.PRESENCE, context -> {
			MessengerPresenceMessage presence = MessengerPresenceMessage.readFrom(context.reader());
			handlePresence(presence);
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	@Override
	public void publishJoin(ServerPlayer player, boolean firstJoin) {
		if (player == null) {
			return;
		}

		networkPlayerNames.put(player.getUUID(), player.getGameProfile().getName());
		bus.send(player, MessengerChannels.PRESENCE, writer -> new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.JOIN,
			player.getUUID(),
			player.getGameProfile().getName(),
			"",
			"",
			firstJoin
		).writeTo(writer));
	}

	@Override
	public void publishLeave(ServerPlayer player) {
		if (player == null) {
			return;
		}

		networkPlayerNames.remove(player.getUUID());
		bus.send(player, MessengerChannels.PRESENCE, writer -> new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.LEAVE,
			player.getUUID(),
			player.getGameProfile().getName(),
			"",
			"",
			false
		).writeTo(writer));
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
		Map<String, String> internalValues = new LinkedHashMap<>();
		internalValues.put("sender_name", player.getGameProfile().getName());
		internalValues.put("player_name", player.getGameProfile().getName());
		internalValues.put("server_name", localServerName);
		internalValues.put("sender_server", localServerName);
		if (commandType == MessengerCommandType.SWITCH_DIRECT
			|| commandType == MessengerCommandType.SEND_DIRECT
			|| commandType == MessengerCommandType.SEND_POKE) {
			String directTarget = targetName != null ? targetName : argument;
			internalValues.put("target_name", directTarget == null ? "" : directTarget);
		}

		var resolution = placeholderResolver.resolve(new dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest(
			player.getUUID(),
			player.getGameProfile().getName(),
			localServerName,
			argument,
			internalValues
		));
		MessengerCommandRequest request = new MessengerCommandRequest(
			MessengerCommandRequest.CURRENT_PROTOCOL,
			requestId,
			commandType,
			player.getUUID(),
			player.getGameProfile().getName(),
			localServerName,
			resolution.resolvedContent(),
			null,
			resolution.exportedValues()
		);

		AtomicReference<Boolean> sent = new AtomicReference<>(false);
		bus.send(player, MessengerChannels.COMMAND, writer -> {
			request.writeTo(writer);
			sent.set(true);
		});
		if (!Boolean.TRUE.equals(sent.get())) {
			return false;
		}

		pendingRequests.put(requestId, new PendingRequest(player.getUUID(), commandType, System.currentTimeMillis()));
		logger.audit("Đã gửi command " + commandType.name() + " reqId=" + requestId + " cho " + player.getGameProfile().getName());
		return true;
	}

	@Override
	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		String token = partial == null ? "" : partial;
		String currentSender = senderName == null ? "" : senderName;

		return networkPlayerNames.values().stream()
			.filter(name -> name != null && !name.isBlank())
			.filter(name -> !name.equalsIgnoreCase(currentSender))
			.filter(name -> token.isEmpty() || name.regionMatches(true, 0, token, 0, token.length()))
			.collect(Collectors.toCollection(LinkedHashSet::new))
			.stream()
			.sorted(Comparator.naturalOrder())
			.limit(20)
			.toList();
	}

	@Override
	public Optional<NeoForgeMessengerResult> latestResult(UUID playerId) {
		if (playerId == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(latestResults.get(playerId));
	}

	@Override
	public BackendPlaceholderResolver placeholderResolver() {
		return placeholderResolver;
	}

	@Override
	public void close() {
		timeoutExecutor.shutdownNow();
		bus.unregisterIncoming(MessengerChannels.RESULT);
		bus.unregisterOutgoing(MessengerChannels.COMMAND);
		bus.unregisterIncoming(MessengerChannels.PRESENCE);
		bus.unregisterOutgoing(MessengerChannels.PRESENCE);
		networkPlayerNames.clear();
		pendingRequests.clear();
		latestResults.clear();
		recentControlCommands.clear();
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

		NeoForgeMessengerResult runtimeResult = new NeoForgeMessengerResult(
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

	private void deliverResult(NeoForgeMessengerResult result) {
		var server = LunaCoreNeoForge.services().server();
		ServerPlayer receiver = server.getPlayerList().getPlayer(result.receiverId());
		if (receiver == null) {
			return;
		}

		String plainMessage = Formatters.stripFormats(result.miniMessage());
		if (plainMessage.isBlank()) {
			return;
		}

		receiver.sendSystemMessage(NeoForgeTextComponents.mini(server, result.miniMessage()));
	}

	private boolean isDuplicateControlCommand(UUID playerId, MessengerCommandType commandType, String argument, String targetName) {
		if (commandType != MessengerCommandType.SWITCH_NETWORK
			&& commandType != MessengerCommandType.SWITCH_SERVER
			&& commandType != MessengerCommandType.SWITCH_DIRECT) {
			return false;
		}

		String normalizedArgument = argument == null ? "" : argument;
		String normalizedTarget = targetName == null ? "" : targetName;
		String fingerprint = commandType.name() + "|" + normalizedArgument + "|" + normalizedTarget;
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
		ServerPlayer player = LunaCoreNeoForge.services().server().getPlayerList().getPlayer(pending.playerId());
		if (player == null) {
			return;
		}

		player.sendSystemMessage(Component.literal(timeoutMessage(pending.commandType())));
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
