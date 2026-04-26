package dev.belikhun.luna.tabbridge.neoforge;

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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RawChannelNeoForgeTabBridgeRuntime implements NeoForgeTabBridgeRuntime {
	private static final PluginMessageChannel TAB_BRIDGE_CHANNEL = PluginMessageChannel.of("tab:bridge-6");
	private static final byte HAS_PERMISSION_ID = 2;
	private static final byte UPDATE_PLACEHOLDER_ID = 8;
	private static final byte PLAYER_JOIN_RESPONSE_ID = 9;

	private final LunaLogger logger;
	private final NeoForgePluginMessagingBus bus;
	private final PermissionService permissionService;
	private final Map<UUID, Map<String, String>> placeholdersByPlayer;
	private final Map<UUID, NeoForgeTabBridgePacket> packetsByPlayer;
	private final Map<UUID, Set<String>> requestedPlaceholdersByPlayer;

	RawChannelNeoForgeTabBridgeRuntime(LunaLogger logger, NeoForgePluginMessagingBus bus, PermissionService permissionService) {
		this.logger = logger.scope("Runtime");
		this.bus = bus;
		this.permissionService = permissionService;
		this.placeholdersByPlayer = new ConcurrentHashMap<>();
		this.packetsByPlayer = new ConcurrentHashMap<>();
		this.requestedPlaceholdersByPlayer = new ConcurrentHashMap<>();
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
		placeholdersByPlayer.put(playerId, Map.copyOf(safeValues));
		pushRequestedPlaceholderUpdates(player, safeValues);
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		if (playerId == null) {
			return Map.of();
		}

		return placeholdersByPlayer.getOrDefault(playerId, Map.of());
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
		packetsByPlayer.remove(playerId);
		requestedPlaceholdersByPlayer.remove(playerId);
	}

	@Override
	public void close() {
		bus.unregisterIncoming(TAB_BRIDGE_CHANNEL);
		bus.unregisterOutgoing(TAB_BRIDGE_CHANNEL);
		placeholdersByPlayer.clear();
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

		placeholdersByPlayer.compute(player.getUUID(), (playerId, existing) -> {
			Map<String, String> updated = new LinkedHashMap<>();
			if (existing != null) {
				updated.putAll(existing);
			}
			updated.put(identifier, value == null ? "" : value);
			return Map.copyOf(updated);
		});
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
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(PLAYER_JOIN_RESPONSE_ID);
			output.writeUTF(currentWorldName(player));
			if (forwardGroup) {
				output.writeUTF("");
			}

			output.writeInt(requestedPlaceholders.size());
			for (String identifier : requestedPlaceholders) {
				output.writeUTF(identifier);
				if (identifier.startsWith("%rel_")) {
					output.writeInt(0);
				} else {
					output.writeUTF(snapshot.getOrDefault(identifier, ""));
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
			sendSinglePlaceholderUpdate(player, identifier, placeholderValues);
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
			output.writeUTF(placeholderValues.getOrDefault(identifier, ""));
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder " + identifier + " cho " + player.getGameProfile().getName() + ": " + exception.getMessage());
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

	private boolean resolvePermission(ServerPlayer player, String permission) {
		if (player == null || permission == null || permission.isBlank() || permissionService == null) {
			return false;
		}

		return permissionService.hasPermission(player.getUUID(), permission);
	}

	private void clearPlayerState(UUID playerId) {
		if (playerId == null) {
			return;
		}

		placeholdersByPlayer.remove(playerId);
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
}
