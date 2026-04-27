package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.messaging.neoforge.NeoForgePluginMessagingBus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RawChannelNeoForgeTabBridgeRuntime implements NeoForgeTabBridgeRuntime {
	private static final PluginMessageChannel TAB_BRIDGE_CHANNEL = PluginMessageChannel.of("tab:bridge-6");
	private static final long INITIAL_PLACEHOLDER_WARMUP_MILLIS = 10_000L;
	private static final byte UPDATE_GAME_MODE_ID = 1;
	private static final byte HAS_PERMISSION_ID = 2;
	private static final byte INVISIBLE_ID = 3;
	private static final byte DISGUISED_ID = 4;
	private static final byte SET_WORLD_ID = 5;
	private static final byte SET_GROUP_ID = 6;
	private static final byte VANISHED_ID = 7;
	private static final byte UPDATE_PLACEHOLDER_ID = 8;
	private static final byte PLAYER_JOIN_RESPONSE_ID = 9;
	private final LunaLogger logger;
	private final NeoForgePluginMessagingBus bus;
	private final PermissionService permissionService;
	private final NeoForgeTabBridgePlayerStateSource playerStateSource;
	private final Map<UUID, Map<String, String>> placeholdersByPlayer;
	private final Map<UUID, Map<String, Map<String, String>>> relationalPlaceholdersByPlayer;
	private final Map<UUID, PlayerBridgeState> stateByPlayer;
	private final Map<UUID, NeoForgeTabBridgePacket> packetsByPlayer;
	private final Map<UUID, Deque<byte[]>> queuedOutgoingPayloadsByPlayer;
	private final Map<UUID, Map<String, RequestedPlaceholderState>> requestedPlaceholdersByPlayer;
	private final Map<UUID, Map<String, String>> lastSentPlaceholderValuesByPlayer;
	private final Map<UUID, Map<String, Map<String, String>>> lastSentRelationalPlaceholderValuesByPlayer;
	private final Set<UUID> readyPlayers;
	private final Map<UUID, Long> placeholderWarmupUntilByPlayer;
	private volatile NeoForgeTabBridgePlaceholderResolver placeholderResolver;

	RawChannelNeoForgeTabBridgeRuntime(LunaLogger logger, NeoForgePluginMessagingBus bus, PermissionService permissionService, NeoForgeTabBridgePlayerStateSource playerStateSource) {
		this.logger = logger.scope("Runtime");
		this.bus = bus;
		this.permissionService = permissionService;
		this.playerStateSource = playerStateSource == null ? new NoopNeoForgeTabBridgePlayerStateSource() : playerStateSource;
		this.placeholdersByPlayer = new ConcurrentHashMap<>();
		this.relationalPlaceholdersByPlayer = new ConcurrentHashMap<>();
		this.stateByPlayer = new ConcurrentHashMap<>();
		this.packetsByPlayer = new ConcurrentHashMap<>();
		this.queuedOutgoingPayloadsByPlayer = new ConcurrentHashMap<>();
		this.requestedPlaceholdersByPlayer = new ConcurrentHashMap<>();
		this.lastSentPlaceholderValuesByPlayer = new ConcurrentHashMap<>();
		this.lastSentRelationalPlaceholderValuesByPlayer = new ConcurrentHashMap<>();
		this.readyPlayers = ConcurrentHashMap.newKeySet();
		this.placeholderWarmupUntilByPlayer = new ConcurrentHashMap<>();
		this.placeholderResolver = null;
		this.bus.registerOutgoing(TAB_BRIDGE_CHANNEL);
		this.bus.registerIncoming(TAB_BRIDGE_CHANNEL, context -> {
			ServerPlayer source = context.source();
			if (source == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			packetsByPlayer.put(source.getUUID(), new NeoForgeTabBridgePacket(
				source.getUUID(),
				source.getGameProfile().getName(),
				context.payload(),
				System.currentTimeMillis()
			));
			markPlayerReady(source.getUUID());
			handleIncoming(source, context.payload());
			flushQueuedMessages(source);
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	@Override
	public void bindPlaceholderResolver(NeoForgeTabBridgePlaceholderResolver placeholderResolver) {
		this.placeholderResolver = placeholderResolver;
	}

	@Override
	public boolean sendRaw(ServerPlayer player, byte[] payload) {
		if (player == null || payload == null) {
			return false;
		}

		UUID playerId = player.getUUID();
		if (!isPlayerReady(playerId)) {
			enqueuePayload(playerId, payload);
			return false;
		}

		Deque<byte[]> queuedPayloads = queuedOutgoingPayloadsByPlayer.get(playerId);
		if (queuedPayloads != null && !queuedPayloads.isEmpty()) {
			enqueuePayload(playerId, payload);
			return flushQueuedMessages(player);
		}

		if (bus.send(player, TAB_BRIDGE_CHANNEL, payload)) {
			return true;
		}

		enqueuePayload(playerId, payload);
		return false;
	}

	@Override
	public void updatePlayerPlaceholders(ServerPlayer player, Map<String, String> placeholderValues) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		Map<String, String> safeValues = new LinkedHashMap<>();
		if (placeholderValues != null) {
			safeValues.putAll(placeholderValues);
		}
		Map<String, String> mergedValues = mergePlaceholderSnapshot(playerId, safeValues);
		flushQueuedMessages(player);
		pushRequestedPlaceholderUpdates(player, mergedValues);
		syncPlayerState(player);
	}

	@Override
	public void updatePlayerRelationalPlaceholders(ServerPlayer player, Map<String, Map<String, String>> placeholderValues) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		Map<String, Map<String, String>> safeValues = new LinkedHashMap<>();
		if (placeholderValues != null) {
			for (Map.Entry<String, Map<String, String>> entry : placeholderValues.entrySet()) {
				String identifier = entry.getKey();
				if (identifier == null || identifier.isBlank()) {
					continue;
				}

				Map<String, String> valuesByTarget = new LinkedHashMap<>();
				Map<String, String> rawValues = entry.getValue();
				if (rawValues != null) {
					for (Map.Entry<String, String> targetEntry : rawValues.entrySet()) {
						String targetName = targetEntry.getKey();
						if (targetName == null || targetName.isBlank()) {
							continue;
						}

						valuesByTarget.put(targetName, targetEntry.getValue() == null ? "" : targetEntry.getValue());
					}
				}

				safeValues.put(identifier, Map.copyOf(valuesByTarget));
			}
		}

		Map<String, Map<String, String>> previousValues = relationalPlaceholdersByPlayer.put(playerId, Map.copyOf(safeValues));
		flushQueuedMessages(player);
		pushRequestedRelationalPlaceholderUpdates(player, previousValues, safeValues);
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		if (playerId == null) {
			return Map.of();
		}

		return placeholdersByPlayer.getOrDefault(playerId, Map.of());
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		if (playerId == null) {
			return Set.of();
		}

		Map<String, RequestedPlaceholderState> registrations = requestedPlaceholdersByPlayer.get(playerId);
		if (registrations == null || registrations.isEmpty()) {
			return Set.of();
		}

		return Set.copyOf(registrations.keySet());
	}

	@Override
	public Optional<NeoForgeTabBridgePacket> latestPacket(UUID playerId) {
		if (playerId == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(packetsByPlayer.get(playerId));
	}

	@Override
	public void removePlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}

		placeholdersByPlayer.remove(playerId);
		relationalPlaceholdersByPlayer.remove(playerId);
		stateByPlayer.remove(playerId);
		packetsByPlayer.remove(playerId);
		queuedOutgoingPayloadsByPlayer.remove(playerId);
		requestedPlaceholdersByPlayer.remove(playerId);
		lastSentPlaceholderValuesByPlayer.remove(playerId);
		lastSentRelationalPlaceholderValuesByPlayer.remove(playerId);
		readyPlayers.remove(playerId);
		placeholderWarmupUntilByPlayer.remove(playerId);
	}

	@Override
	public void close() {
		bus.unregisterIncoming(TAB_BRIDGE_CHANNEL);
		bus.unregisterOutgoing(TAB_BRIDGE_CHANNEL);
		placeholdersByPlayer.clear();
		relationalPlaceholdersByPlayer.clear();
		stateByPlayer.clear();
		packetsByPlayer.clear();
		queuedOutgoingPayloadsByPlayer.clear();
		requestedPlaceholdersByPlayer.clear();
		lastSentPlaceholderValuesByPlayer.clear();
		lastSentRelationalPlaceholderValuesByPlayer.clear();
		readyPlayers.clear();
		placeholderWarmupUntilByPlayer.clear();
	}

	private void handleIncoming(ServerPlayer player, byte[] payload) {
		if (payload == null || payload.length == 0) {
			return;
		}

		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			String action = input.readUTF();
			switch (action) {
				case "PlayerJoin" -> handlePlayerJoin(player, input);
				case "Expansion" -> handleExpansionUpdate(player, input);
				case "Placeholder" -> handlePlaceholderRegistration(player, input);
				case "Permission" -> handlePermissionRequest(player, input);
				case "Unload" -> clearPlayerState(player.getUUID());
				default -> logger.debug("Bỏ qua TAB bridge action chưa hỗ trợ: " + action);
			}
		} catch (IOException exception) {
			logger.debug("Không thể giải mã TAB bridge payload cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void handlePlayerJoin(ServerPlayer player, DataInputStream input) throws IOException {
		input.readInt();
		boolean forwardGroup = input.readBoolean();
		Map<String, RequestedPlaceholderState> requestedPlaceholders = readPlaceholderRegistrations(input);
		readReplacementRules(input);
		if (input.available() > 0) {
			input.readBoolean();
		}

		clearQueuedMessages(player.getUUID());
		requestedPlaceholdersByPlayer.put(player.getUUID(), requestedPlaceholders);
		startPlaceholderWarmup(player.getUUID());
		sendPlayerJoinResponse(player, forwardGroup, requestedPlaceholders);
		PlayerBridgeState currentState = captureState(player);
		sendInitialStatePackets(player, currentState);
		stateByPlayer.put(player.getUUID(), currentState);
	}

	private void handlePlaceholderRegistration(ServerPlayer player, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		int refreshMillis = 50;
		if (input.available() >= Integer.BYTES) {
			refreshMillis = input.readInt();
		}

		if (!isSupportedRequestedPlaceholderIdentifier(identifier)) {
			return;
		}

		registerPlaceholder(player.getUUID(), identifier, refreshMillis);
		startPlaceholderWarmup(player.getUUID());
		if (identifier != null && identifier.startsWith("%rel_")) {
			Map<String, String> valuesByTarget = relationalPlaceholdersByPlayer
				.getOrDefault(player.getUUID(), Map.of())
				.get(identifier);
			sendRelationalPlaceholderUpdates(player, identifier, valuesByTarget, Map.of());
			return;
		}

		Map<String, String> snapshot = placeholdersByPlayer.getOrDefault(player.getUUID(), Map.of());
		String resolvedValue = resolvePlaceholderValue(player, identifier, snapshot);
		sendSinglePlaceholderUpdate(player, identifier, resolvedValue, snapshot);
		rememberSentPlaceholderValue(player.getUUID(), identifier, resolvedValue);
	}

	private void handlePermissionRequest(ServerPlayer player, DataInputStream input) throws IOException {
		String permission = input.readUTF();
		sendPermissionResponse(player, permission, resolvePermission(player, permission));
	}

	private void handleExpansionUpdate(ServerPlayer player, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		String value = input.readUTF();
		if (identifier == null || identifier.isBlank()) {
			return;
		}

		mergePlaceholderSnapshot(player.getUUID(), Map.of(identifier, value == null ? "" : value));
	}

	private Map<String, String> mergePlaceholderSnapshot(UUID playerId, Map<String, String> incomingValues) {
		if (playerId == null) {
			return Map.of();
		}

		Map<String, String> merged = placeholdersByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, String> updated = existing == null ? new ConcurrentHashMap<>() : existing;

			if (incomingValues != null) {
				for (Map.Entry<String, String> entry : incomingValues.entrySet()) {
					String identifier = entry.getKey();
					if (identifier == null || identifier.isBlank()) {
						continue;
					}

					String normalizedValue = entry.getValue() == null ? "" : entry.getValue();
					updated.put(identifier, normalizedValue);

					String normalizedIdentifier = normalizeSnapshotLookupKey(identifier);
					if (!normalizedIdentifier.isEmpty() && !normalizedIdentifier.equals(identifier)) {
						updated.put(normalizedIdentifier, normalizedValue);
					}
				}
			}

			return updated;
		});

		return merged == null ? Map.of() : merged;
	}

	private Map<String, RequestedPlaceholderState> readPlaceholderRegistrations(DataInputStream input) throws IOException {
		int placeholderCount = input.readInt();
		Map<String, RequestedPlaceholderState> placeholders = new LinkedHashMap<>();
		for (int index = 0; index < placeholderCount; index++) {
			String identifier = input.readUTF();
			int refreshMillis = input.readInt();
			if (!isSupportedRequestedPlaceholderIdentifier(identifier)) {
				continue;
			}

			placeholders.put(identifier, new RequestedPlaceholderState(normalizeRefreshMillis(refreshMillis)));
		}
		return placeholders;
	}

	private void readReplacementRules(DataInputStream input) throws IOException {
		int replacementCount = input.readInt();
		for (int index = 0; index < replacementCount; index++) {
			input.readUTF();
			int ruleCount = input.readInt();
			for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex++) {
				input.readUTF();
				input.readUTF();
			}
		}
	}

	private void sendPlayerJoinResponse(ServerPlayer player, boolean forwardGroup, Map<String, RequestedPlaceholderState> requestedPlaceholders) {
		Map<String, String> snapshot = placeholdersByPlayer.getOrDefault(player.getUUID(), Map.of());
		Map<String, Map<String, String>> relationalSnapshot = relationalPlaceholdersByPlayer.getOrDefault(player.getUUID(), Map.of());
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(PLAYER_JOIN_RESPONSE_ID);
			output.writeUTF(currentWorldName(player));
			if (forwardGroup) {
				output.writeUTF(resolveGroupName(player));
			}

			output.writeInt(requestedPlaceholders.size());
			for (String identifier : requestedPlaceholders.keySet()) {
				output.writeUTF(identifier);
				if (identifier.startsWith("%rel_")) {
					Map<String, String> valuesByTarget = relationalSnapshot.get(identifier);
					writeRelationalPlaceholderMap(output, valuesByTarget);
				} else {
					String value = resolvePlaceholderValue(player, identifier, snapshot);
					output.writeUTF(value);
				}
			}

			output.writeInt(currentGameModeId(player));
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB PlayerJoinResponse cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void pushRequestedPlaceholderUpdates(ServerPlayer player, Map<String, String> placeholderValues) {
		UUID playerId = player.getUUID();
		boolean warmupActive = isPlaceholderWarmupActive(playerId);
		Map<String, RequestedPlaceholderState> requestedPlaceholders = requestedPlaceholdersByPlayer.get(playerId);
		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		for (Map.Entry<String, RequestedPlaceholderState> entry : requestedPlaceholders.entrySet()) {
			String identifier = entry.getKey();
			if (identifier.startsWith("%rel_")) {
				continue;
			}

			RequestedPlaceholderState state = entry.getValue();
			if (state == null || !state.shouldEvaluate()) {
				continue;
			}

			String value = resolvePlaceholderValue(player, identifier, placeholderValues);
			if (!warmupActive && !placeholderValueChanged(playerId, identifier, value)) {
				markPlaceholderEvaluated(playerId, identifier);
				continue;
			}

			sendSinglePlaceholderUpdate(player, identifier, value, placeholderValues);
			rememberSentPlaceholderValue(playerId, identifier, value);
			markPlaceholderEvaluated(playerId, identifier);
		}
	}

	private void pushRequestedRelationalPlaceholderUpdates(ServerPlayer player, Map<String, Map<String, String>> previousValues, Map<String, Map<String, String>> placeholderValues) {
		Map<String, RequestedPlaceholderState> requestedPlaceholders = requestedPlaceholdersByPlayer.get(player.getUUID());
		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		Map<String, Map<String, String>> currentRelationalValues = placeholderValues == null ? Map.of() : placeholderValues;
		for (Map.Entry<String, RequestedPlaceholderState> entry : requestedPlaceholders.entrySet()) {
			String identifier = entry.getKey();
			if (!identifier.startsWith("%rel_")) {
				continue;
			}

			RequestedPlaceholderState state = entry.getValue();
			if (state == null || !state.shouldEvaluate()) {
				continue;
			}

			Map<String, String> currentValuesByTarget = currentRelationalValues.get(identifier);
			Map<String, String> lastSentValuesByTarget = lastSentRelationalPlaceholderValuesByPlayer
				.getOrDefault(player.getUUID(), Map.of())
				.getOrDefault(identifier, Map.of());
			sendRelationalPlaceholderUpdates(player, identifier, currentValuesByTarget, lastSentValuesByTarget);
			sendClearedRelationalPlaceholderUpdates(player, identifier, lastSentValuesByTarget, currentValuesByTarget);
			rememberSentRelationalPlaceholderValues(player.getUUID(), identifier, currentValuesByTarget);
			markPlaceholderEvaluated(player.getUUID(), identifier);
		}
	}

	private void sendSinglePlaceholderUpdate(ServerPlayer player, String identifier, String resolvedValue, Map<String, String> placeholderValues) {
		if (player == null || identifier == null || identifier.isBlank() || identifier.startsWith("%rel_")) {
			return;
		}

		String safeResolvedValue = resolvedValue == null ? "" : resolvedValue;

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_PLACEHOLDER_ID);
			output.writeUTF(identifier);
			output.writeUTF(safeResolvedValue);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder " + identifier + " cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private String resolvePlaceholderValue(ServerPlayer player, String identifier, Map<String, String> placeholderValues) {
		if (shouldPreferResolver(identifier)) {
			NeoForgeTabBridgePlaceholderResolver resolver = placeholderResolver;
			if (resolver != null && player != null) {
				String resolved = resolver.resolve(player, identifier);
				if (resolved != null) {
					return normalizeBridgePlaceholderValue(identifier, resolved);
				}
			}
		}

		if (hasSnapshotValue(identifier, placeholderValues)) {
			return normalizeBridgePlaceholderValue(identifier, resolveSnapshotValue(identifier, placeholderValues));
		}

		NeoForgeTabBridgePlaceholderResolver resolver = placeholderResolver;
		if (resolver != null && player != null) {
			String resolved = resolver.resolve(player, identifier);
			if (resolved != null) {
				return normalizeBridgePlaceholderValue(identifier, resolved);
			}
		}

		return normalizeBridgePlaceholderValue(identifier, resolveSnapshotValue(identifier, placeholderValues));
	}

	private boolean shouldPreferResolver(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return false;
		}

		String normalized = normalizeSnapshotLookupKey(identifier);
		return normalized.startsWith("luna_") && normalized.endsWith("_safe");
	}

	private boolean isSupportedRequestedPlaceholderIdentifier(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return false;
		}

		String trimmed = identifier.trim();
		if (trimmed.length() < 3 || !trimmed.startsWith("%") || !trimmed.endsWith("%")) {
			return false;
		}

		String inner = trimmed.substring(1, trimmed.length() - 1);
		if (inner.isBlank()) {
			return false;
		}

		for (int index = 0; index < inner.length(); index++) {
			char character = inner.charAt(index);
			if (character == '\n' || character == '\r' || Character.isISOControl(character)) {
				return false;
			}

			Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(character);
			if (unicodeBlock == Character.UnicodeBlock.BLOCK_ELEMENTS) {
				return false;
			}
		}

		return true;
	}

	private String normalizeBridgePlaceholderValue(String identifier, String value) {
		if (value == null) {
			return "";
		}

		return value;
	}

	private boolean hasSnapshotValue(String identifier, Map<String, String> placeholderValues) {
		if (identifier == null || identifier.isBlank() || placeholderValues == null || placeholderValues.isEmpty()) {
			return false;
		}

		if (placeholderValues.containsKey(identifier)) {
			return true;
		}

		String normalizedKey = normalizeSnapshotLookupKey(identifier);
		return !normalizedKey.isEmpty() && placeholderValues.containsKey(normalizedKey);
	}

	private void sendRelationalPlaceholderUpdates(ServerPlayer player, String identifier, Map<String, String> valuesByTarget, Map<String, String> previousValuesByTarget) {
		if (player == null || identifier == null || identifier.isBlank() || !identifier.startsWith("%rel_") || valuesByTarget == null || valuesByTarget.isEmpty()) {
			return;
		}

		for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
			String targetName = entry.getKey();
			String value = entry.getValue() == null ? "" : entry.getValue();
			if (value.equals(previousValuesByTarget.getOrDefault(targetName, null))) {
				continue;
			}

			sendRelationalPlaceholderUpdate(player, identifier, targetName, value);
		}
	}

	private void sendClearedRelationalPlaceholderUpdates(ServerPlayer player, String identifier, Map<String, String> previousValuesByTarget, Map<String, String> currentValuesByTarget) {
		if (player == null || identifier == null || identifier.isBlank() || !identifier.startsWith("%rel_") || previousValuesByTarget == null || previousValuesByTarget.isEmpty()) {
			return;
		}

		Map<String, String> safeCurrentValues = currentValuesByTarget == null ? Map.of() : currentValuesByTarget;
		for (String targetName : previousValuesByTarget.keySet()) {
			if (targetName == null || targetName.isBlank() || safeCurrentValues.containsKey(targetName)) {
				continue;
			}

			sendRelationalPlaceholderUpdate(player, identifier, targetName, "");
		}
	}

	private void sendRelationalPlaceholderUpdate(ServerPlayer player, String identifier, String targetName, String value) {
		if (player == null || identifier == null || identifier.isBlank() || !identifier.startsWith("%rel_") || targetName == null || targetName.isBlank()) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_PLACEHOLDER_ID);
			output.writeUTF(identifier);
			output.writeUTF(targetName);
			output.writeUTF(value == null ? "" : value);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder quan hệ " + identifier + " cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void writeRelationalPlaceholderMap(DataOutputStream output, Map<String, String> valuesByTarget) throws IOException {
		if (output == null || valuesByTarget == null || valuesByTarget.isEmpty()) {
			output.writeInt(0);
			return;
		}

		output.writeInt(valuesByTarget.size());
		for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
			output.writeUTF(entry.getKey());
			output.writeUTF(entry.getValue() == null ? "" : entry.getValue());
		}
	}

	private String resolveSnapshotValue(String identifier, Map<String, String> placeholderValues) {
		if (identifier == null || identifier.isBlank() || placeholderValues == null || placeholderValues.isEmpty()) {
			return "";
		}

		String direct = placeholderValues.get(identifier);
		if (direct != null) {
			return direct;
		}

		String normalizedKey = normalizeSnapshotLookupKey(identifier);
		if (normalizedKey.isEmpty()) {
			return "";
		}

		return placeholderValues.getOrDefault(normalizedKey, "");
	}

	private String normalizeSnapshotLookupKey(String identifier) {
		if (identifier == null) {
			return "";
		}

		String normalized = identifier.trim().toLowerCase(Locale.ROOT);
		if (normalized.length() >= 2 && normalized.startsWith("%") && normalized.endsWith("%")) {
			return normalized.substring(1, normalized.length() - 1);
		}

		return normalized;
	}

	private void registerPlaceholder(UUID playerId, String identifier, int refreshMillis) {
		if (playerId == null || !isSupportedRequestedPlaceholderIdentifier(identifier)) {
			return;
		}

		requestedPlaceholdersByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, RequestedPlaceholderState> updated = new LinkedHashMap<>();
			if (existing != null) {
				updated.putAll(existing);
			}
			updated.put(identifier, new RequestedPlaceholderState(normalizeRefreshMillis(refreshMillis)));
			return Map.copyOf(updated);
		});
	}

	private void enqueuePayload(UUID playerId, byte[] payload) {
		if (playerId == null || payload == null) {
			return;
		}

		queuedOutgoingPayloadsByPlayer.compute(playerId, (ignored, existing) -> {
			Deque<byte[]> queue = existing == null ? new ArrayDeque<>() : existing;
			synchronized (queue) {
				queue.addLast(Arrays.copyOf(payload, payload.length));
			}
			return queue;
		});
	}

	private boolean flushQueuedMessages(ServerPlayer player) {
		if (player == null) {
			return false;
		}

		UUID playerId = player.getUUID();
		if (!isPlayerReady(playerId)) {
			return false;
		}

		Deque<byte[]> queuedPayloads = queuedOutgoingPayloadsByPlayer.get(playerId);
		if (queuedPayloads == null || queuedPayloads.isEmpty()) {
			return true;
		}

		while (true) {
			byte[] nextPayload;
			synchronized (queuedPayloads) {
				nextPayload = queuedPayloads.peekFirst();
			}
			if (nextPayload == null) {
				queuedOutgoingPayloadsByPlayer.remove(playerId, queuedPayloads);
				return true;
			}

			if (!bus.send(player, TAB_BRIDGE_CHANNEL, nextPayload)) {
				return false;
			}

			synchronized (queuedPayloads) {
				queuedPayloads.pollFirst();
				if (queuedPayloads.isEmpty()) {
					queuedOutgoingPayloadsByPlayer.remove(playerId, queuedPayloads);
					return true;
				}
			}
		}
	}

	private void clearQueuedMessages(UUID playerId) {
		if (playerId == null) {
			return;
		}

		queuedOutgoingPayloadsByPlayer.remove(playerId);
	}

	private void markPlayerReady(UUID playerId) {
		if (playerId == null) {
			return;
		}

		readyPlayers.add(playerId);
	}

	private boolean isPlayerReady(UUID playerId) {
		return playerId != null && readyPlayers.contains(playerId);
	}

	private void startPlaceholderWarmup(UUID playerId) {
		if (playerId == null) {
			return;
		}

		placeholderWarmupUntilByPlayer.put(playerId, System.currentTimeMillis() + INITIAL_PLACEHOLDER_WARMUP_MILLIS);
	}

	private boolean isPlaceholderWarmupActive(UUID playerId) {
		if (playerId == null) {
			return false;
		}

		Long warmupUntilMillis = placeholderWarmupUntilByPlayer.get(playerId);
		if (warmupUntilMillis == null) {
			return false;
		}

		if (System.currentTimeMillis() <= warmupUntilMillis) {
			return true;
		}

		placeholderWarmupUntilByPlayer.remove(playerId, warmupUntilMillis);
		return false;
	}

	private int normalizeRefreshMillis(int refreshMillis) {
		if (refreshMillis == -1) {
			return -1;
		}

		return Math.max(50, refreshMillis);
	}

	private boolean placeholderValueChanged(UUID playerId, String identifier, String value) {
		if (playerId == null || identifier == null || identifier.isBlank()) {
			return false;
		}

		Map<String, String> lastSentValues = lastSentPlaceholderValuesByPlayer.get(playerId);
		if (lastSentValues == null) {
			return true;
		}

		return !java.util.Objects.equals(lastSentValues.get(identifier), value);
	}

	private void rememberSentPlaceholderValue(UUID playerId, String identifier, String value) {
		if (playerId == null || identifier == null || identifier.isBlank()) {
			return;
		}

		lastSentPlaceholderValuesByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, String> updated = existing == null ? new ConcurrentHashMap<>() : existing;
			updated.put(identifier, value == null ? "" : value);
			return updated;
		});
	}

	private void rememberSentRelationalPlaceholderValues(UUID playerId, String identifier, Map<String, String> valuesByTarget) {
		if (playerId == null || identifier == null || identifier.isBlank()) {
			return;
		}

		Map<String, String> safeValues = new LinkedHashMap<>();
		if (valuesByTarget != null) {
			for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
				String targetName = entry.getKey();
				if (targetName == null || targetName.isBlank()) {
					continue;
				}

				safeValues.put(targetName, entry.getValue() == null ? "" : entry.getValue());
			}
		}

		lastSentRelationalPlaceholderValuesByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, Map<String, String>> updated = existing == null ? new ConcurrentHashMap<>() : existing;
			updated.put(identifier, new ConcurrentHashMap<>(safeValues));
			return updated;
		});
	}

	private void markPlaceholderEvaluated(UUID playerId, String identifier) {
		if (playerId == null || identifier == null || identifier.isBlank()) {
			return;
		}

		Map<String, RequestedPlaceholderState> registrations = requestedPlaceholdersByPlayer.get(playerId);
		if (registrations == null) {
			return;
		}

		RequestedPlaceholderState state = registrations.get(identifier);
		if (state != null) {
			state.advance();
		}
	}

	private static final class RequestedPlaceholderState {
		private final int refreshMillis;
		private volatile long nextEvaluationAtMillis;
		private volatile boolean initialEvaluationPending;

		private RequestedPlaceholderState(int refreshMillis) {
			this.refreshMillis = refreshMillis;
			this.nextEvaluationAtMillis = 0L;
			this.initialEvaluationPending = true;
		}

		private boolean shouldEvaluate() {
			if (initialEvaluationPending) {
				return true;
			}

			if (refreshMillis == -1) {
				return false;
			}

			return System.currentTimeMillis() >= nextEvaluationAtMillis;
		}

		private void advance() {
			initialEvaluationPending = false;

			if (refreshMillis == -1) {
				nextEvaluationAtMillis = Long.MAX_VALUE;
				return;
			}

			nextEvaluationAtMillis = System.currentTimeMillis() + refreshMillis;
		}
	}

	private void sendPermissionResponse(ServerPlayer player, String permission, boolean value) {
		if (player == null || permission == null || permission.isBlank()) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(HAS_PERMISSION_ID);
			output.writeUTF(permission);
			output.writeBoolean(value);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB HasPermission " + permission + " cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void syncPlayerState(ServerPlayer player) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		PlayerBridgeState current = captureState(player);
		PlayerBridgeState previous = stateByPlayer.put(playerId, current);
		if (previous == null) {
			return;
		}

		if (!previous.worldName().equals(current.worldName())) {
			sendWorldUpdate(player, current.worldName());
		}

		if (!previous.groupName().equals(current.groupName())) {
			sendGroupUpdate(player, current.groupName());
		}

		if (previous.gameModeId() != current.gameModeId()) {
			sendGameModeUpdate(player, current.gameModeId());
		}

		if (previous.invisible() != current.invisible()) {
			sendInvisibleUpdate(player, current.invisible());
		}

		if (previous.disguised() != current.disguised()) {
			sendDisguisedUpdate(player, current.disguised());
		}

		if (previous.vanished() != current.vanished()) {
			sendVanishedUpdate(player, current.vanished());
		}
	}

	private PlayerBridgeState captureState(ServerPlayer player) {
		NeoForgeTabBridgePlayerState playerState = resolvePlayerState(player);
		return new PlayerBridgeState(
			currentWorldName(player),
			resolveGroupName(player),
			currentGameModeId(player),
			isInvisible(player),
			playerState.disguised(),
			playerState.vanished()
		);
	}

	private void sendInitialStatePackets(ServerPlayer player, PlayerBridgeState state) {
		if (player == null || state == null) {
			return;
		}

		if (state.invisible()) {
			sendInvisibleUpdate(player, true);
		}

		if (state.disguised()) {
			sendDisguisedUpdate(player, true);
		}

		if (state.vanished()) {
			sendVanishedUpdate(player, true);
		}
	}

	private void sendGameModeUpdate(ServerPlayer player, int gameModeId) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_GAME_MODE_ID);
			output.writeInt(gameModeId);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdateGameMode cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void sendInvisibleUpdate(ServerPlayer player, boolean invisible) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(INVISIBLE_ID);
			output.writeBoolean(invisible);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB Invisible cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void sendDisguisedUpdate(ServerPlayer player, boolean disguised) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(DISGUISED_ID);
			output.writeBoolean(disguised);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB Disguised cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void sendVanishedUpdate(ServerPlayer player, boolean vanished) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(VANISHED_ID);
			output.writeBoolean(vanished);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB Vanished cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void sendWorldUpdate(ServerPlayer player, String worldName) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(SET_WORLD_ID);
			output.writeUTF(worldName == null ? "unknown" : worldName);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB SetWorld cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void sendGroupUpdate(ServerPlayer player, String groupName) {
		if (player == null) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(SET_GROUP_ID);
			output.writeUTF(groupName == null ? "" : groupName);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB SetGroup cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private boolean resolvePermission(ServerPlayer player, String permission) {
		if (player == null || permission == null || permission.isBlank() || permissionService == null) {
			return false;
		}

		return permissionService.hasPermission(player.getUUID(), permission);
	}

	private String resolveGroupName(ServerPlayer player) {
		if (player == null || permissionService == null) {
			return "";
		}

		String groupName = permissionService.getGroupName(player.getUUID());
		return groupName == null ? "" : groupName;
	}

	private void clearPlayerState(UUID playerId) {
		if (playerId == null) {
			return;
		}

		placeholdersByPlayer.remove(playerId);
		relationalPlaceholdersByPlayer.remove(playerId);
		stateByPlayer.remove(playerId);
		packetsByPlayer.remove(playerId);
		queuedOutgoingPayloadsByPlayer.remove(playerId);
		requestedPlaceholdersByPlayer.remove(playerId);
		lastSentPlaceholderValuesByPlayer.remove(playerId);
		lastSentRelationalPlaceholderValuesByPlayer.remove(playerId);
		readyPlayers.remove(playerId);
		placeholderWarmupUntilByPlayer.remove(playerId);
	}

	private String currentWorldName(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		if (level == null || level.dimension() == null || level.dimension().location() == null) {
			return "unknown";
		}

		return level.dimension().location().toString();
	}

	private int currentGameModeId(ServerPlayer player) {
		try {
			return player.gameMode.getGameModeForPlayer().getId();
		} catch (RuntimeException exception) {
			return 0;
		}
	}

	private boolean isInvisible(ServerPlayer player) {
		return player != null && player.isInvisible();
	}

	private NeoForgeTabBridgePlayerState resolvePlayerState(ServerPlayer player) {
		if (player == null) {
			return NeoForgeTabBridgePlayerState.DEFAULT;
		}

		NeoForgeTabBridgePlayerState state = playerStateSource.resolve(player);
		return state == null ? NeoForgeTabBridgePlayerState.DEFAULT : state;
	}

	private record PlayerBridgeState(String worldName, String groupName, int gameModeId, boolean invisible, boolean disguised, boolean vanished) {
		private PlayerBridgeState {
			worldName = worldName == null ? "unknown" : worldName;
			groupName = groupName == null ? "" : groupName;
		}
	}
}
