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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RawChannelNeoForgeTabBridgeRuntime implements NeoForgeTabBridgeRuntime {
	private static final PluginMessageChannel TAB_BRIDGE_CHANNEL = PluginMessageChannel.of("tab:bridge-6");
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
	private final Map<UUID, Set<String>> requestedPlaceholdersByPlayer;
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
		this.requestedPlaceholdersByPlayer = new ConcurrentHashMap<>();
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
			handleIncoming(source, context.payload());
			logger.debug("Đã nhận TAB bridge payload bytes=" + context.payload().length + " cho " + source.getGameProfile().getName());
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

		return bus.send(player, TAB_BRIDGE_CHANNEL, payload);
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

		return requestedPlaceholdersByPlayer.getOrDefault(playerId, Set.of());
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
		requestedPlaceholdersByPlayer.remove(playerId);
	}

	@Override
	public void close() {
		bus.unregisterIncoming(TAB_BRIDGE_CHANNEL);
		bus.unregisterOutgoing(TAB_BRIDGE_CHANNEL);
		placeholdersByPlayer.clear();
		relationalPlaceholdersByPlayer.clear();
		stateByPlayer.clear();
		packetsByPlayer.clear();
		requestedPlaceholdersByPlayer.clear();
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
		Set<String> requestedPlaceholders = readPlaceholderRegistrations(input);
		readReplacementRules(input);
		if (input.available() > 0) {
			input.readBoolean();
		}

		requestedPlaceholdersByPlayer.put(player.getUUID(), Set.copyOf(requestedPlaceholders));
		sendPlayerJoinResponse(player, forwardGroup, requestedPlaceholders);
		PlayerBridgeState currentState = captureState(player);
		sendInitialStatePackets(player, currentState);
		stateByPlayer.put(player.getUUID(), currentState);
	}

	private void handlePlaceholderRegistration(ServerPlayer player, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		if (input.available() >= Integer.BYTES) {
			input.readInt();
		}

		requestedPlaceholdersByPlayer.compute(player.getUUID(), (playerId, existing) -> {
			Set<String> updated = new LinkedHashSet<>();
			if (existing != null) {
				updated.addAll(existing);
			}
			updated.add(identifier);
			return Set.copyOf(updated);
		});
		if (identifier != null && identifier.startsWith("%rel_")) {
			sendRelationalPlaceholderUpdates(player, identifier, relationalPlaceholdersByPlayer
				.getOrDefault(player.getUUID(), Map.of())
				.get(identifier));
			return;
		}

		sendSinglePlaceholderUpdate(player, identifier, placeholdersByPlayer.getOrDefault(player.getUUID(), Map.of()));
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
			Map<String, String> updated = new LinkedHashMap<>();
			if (existing != null) {
				updated.putAll(existing);
			}

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

			return Map.copyOf(updated);
		});

		return merged == null ? Map.of() : merged;
	}

	private Set<String> readPlaceholderRegistrations(DataInputStream input) throws IOException {
		int placeholderCount = input.readInt();
		Set<String> placeholders = new LinkedHashSet<>();
		for (int index = 0; index < placeholderCount; index++) {
			placeholders.add(input.readUTF());
			input.readInt();
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

	private void sendPlayerJoinResponse(ServerPlayer player, boolean forwardGroup, Set<String> requestedPlaceholders) {
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
			for (String identifier : requestedPlaceholders) {
				output.writeUTF(identifier);
				if (identifier.startsWith("%rel_")) {
					writeRelationalPlaceholderMap(output, relationalSnapshot.get(identifier));
				} else {
					output.writeUTF(resolvePlaceholderValue(player, identifier, snapshot));
				}
			}

			output.writeInt(currentGameModeId(player));
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB PlayerJoinResponse cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private void pushRequestedPlaceholderUpdates(ServerPlayer player, Map<String, String> placeholderValues) {
		Set<String> requestedPlaceholders = requestedPlaceholdersByPlayer.get(player.getUUID());
		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		for (String identifier : requestedPlaceholders) {
			if (identifier.startsWith("%rel_")) {
				continue;
			}

			sendSinglePlaceholderUpdate(player, identifier, placeholderValues);
		}
	}

	private void pushRequestedRelationalPlaceholderUpdates(ServerPlayer player, Map<String, Map<String, String>> previousValues, Map<String, Map<String, String>> placeholderValues) {
		Set<String> requestedPlaceholders = requestedPlaceholdersByPlayer.get(player.getUUID());
		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		Map<String, Map<String, String>> currentRelationalValues = placeholderValues == null ? Map.of() : placeholderValues;
		Map<String, Map<String, String>> previousRelationalValues = previousValues == null ? Map.of() : previousValues;
		for (String identifier : requestedPlaceholders) {
			if (!identifier.startsWith("%rel_")) {
				continue;
			}

			Map<String, String> currentValuesByTarget = currentRelationalValues.get(identifier);
			sendRelationalPlaceholderUpdates(player, identifier, currentValuesByTarget);
			sendClearedRelationalPlaceholderUpdates(player, identifier, previousRelationalValues.get(identifier), currentValuesByTarget);
		}
	}

	private void sendSinglePlaceholderUpdate(ServerPlayer player, String identifier, Map<String, String> placeholderValues) {
		if (player == null || identifier == null || identifier.isBlank() || identifier.startsWith("%rel_")) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_PLACEHOLDER_ID);
			output.writeUTF(identifier);
			output.writeUTF(resolvePlaceholderValue(player, identifier, placeholderValues));
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder " + identifier + " cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
		}
	}

	private String resolvePlaceholderValue(ServerPlayer player, String identifier, Map<String, String> placeholderValues) {
		NeoForgeTabBridgePlaceholderResolver resolver = placeholderResolver;
		if (resolver != null && player != null) {
			String resolved = resolver.resolve(player, identifier);
			if (resolved != null) {
				return resolved;
			}
		}

		return resolveSnapshotValue(identifier, placeholderValues);
	}

	private void sendRelationalPlaceholderUpdates(ServerPlayer player, String identifier, Map<String, String> valuesByTarget) {
		if (player == null || identifier == null || identifier.isBlank() || !identifier.startsWith("%rel_") || valuesByTarget == null || valuesByTarget.isEmpty()) {
			return;
		}

		for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
			sendRelationalPlaceholderUpdate(player, identifier, entry.getKey(), entry.getValue());
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
		requestedPlaceholdersByPlayer.remove(playerId);
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
