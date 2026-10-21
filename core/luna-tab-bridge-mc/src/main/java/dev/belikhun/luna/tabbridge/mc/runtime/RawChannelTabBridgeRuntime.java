package dev.belikhun.luna.tabbridge.mc.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * TAB's bridge protocol over a plugin-message channel, one instance per server.
 *
 * The wire is TAB's own ({@code tab:bridge-6}): inbound frames name an action
 * with a UTF string, outbound frames start with a byte id and carry positional
 * fields. What this class adds is bookkeeping: which identifiers TAB asked for
 * and how often, what was last sent so unchanged values stay quiet, a bounded
 * queue for frames sent before TAB has said hello for the player, and a record
 * of what was refused and why, so an operator can ask.
 *
 * Every value comes from the core's placeholder engine, which reads samples
 * only; the refresh therefore stays cheap even for a full tab list.
 */
final class RawChannelTabBridgeRuntime implements TabBridgeRuntime {
	private static final PluginMessageChannel TAB_BRIDGE_CHANNEL = PluginMessageChannel.of("tab:bridge-6");
	/** After a join or a new registration, every value is sent once even if unchanged. */
	private static final long INITIAL_PLACEHOLDER_WARMUP_MILLIS = 10_000L;
	/** How often a value is re-sent during the warmup, whatever its refresh. */
	private static final long WARMUP_RESEND_MILLIS = 1_000L;
	/** Frames kept per player while TAB has not said hello; older ones are dropped. */
	private static final int MAX_QUEUED_FRAMES = 256;
	/** TAB's own minimum, raised: below this the client strobes. */
	private static final int MIN_REFRESH_MILLIS = 100;
	private static final int LARGE_PLACEHOLDER_IDENTIFIER_LENGTH = 256;
	private static final int LARGE_PLACEHOLDER_VALUE_LENGTH = 512;
	private static final int PLACEHOLDER_PREVIEW_LENGTH = 160;
	private static final String RELATIONAL_PREFIX = "%rel_";
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
	private final PluginMessageBus<ServerPlayer, ServerPlayer> bus;
	private final PermissionService permissionService;
	private final TabBridgePlayerStateSource playerStateSource;
	private final PlaceholderRuntime<ServerPlayer> placeholders;
	private final TabBridgeRelationalPlaceholderSource relationalSource;
	private final Map<UUID, PlayerBridge> players = new ConcurrentHashMap<>();
	private final Map<String, String> rejectedIdentifiers = new ConcurrentHashMap<>();
	private final Set<String> unmatchedIdentifiers = ConcurrentHashMap.newKeySet();
	private final LongAdder framesSent = new LongAdder();
	private final LongAdder framesReceived = new LongAdder();
	private final LongAdder framesDropped = new LongAdder();
	private final LongAdder placeholderUpdatesSent = new LongAdder();

	RawChannelTabBridgeRuntime(
		LunaLogger logger,
		PluginMessageBus<ServerPlayer, ServerPlayer> bus,
		PermissionService permissionService,
		TabBridgePlayerStateSource playerStateSource,
		PlaceholderRuntime<ServerPlayer> placeholders,
		TabBridgeRelationalPlaceholderSource relationalSource
	) {
		this.logger = logger.scope("Runtime");
		this.bus = bus;
		this.permissionService = permissionService;
		this.playerStateSource = playerStateSource == null ? new NoopTabBridgePlayerStateSource() : playerStateSource;
		this.placeholders = placeholders;
		this.relationalSource = relationalSource == null ? new NoopTabBridgeRelationalPlaceholderSource() : relationalSource;
		this.bus.registerOutgoing(TAB_BRIDGE_CHANNEL);
		this.bus.registerIncoming(TAB_BRIDGE_CHANNEL, context -> {
			ServerPlayer source = context.source();

			if (source == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			framesReceived.increment();
			PlayerBridge bridge = bridge(source.getUUID());
			bridge.latestPacket = new TabBridgePacket(source.getUUID(), nameOf(source), context.payload(), System.currentTimeMillis());
			bridge.ready = true;
			handleIncoming(source, bridge, context.payload());
			flushQueue(source, bridge);
			return PluginMessageDispatchResult.HANDLED;
		});
	}

	// ------------------------------------------------------------------ public

	@Override
	public boolean sendRaw(ServerPlayer player, byte[] payload) {
		if (player == null || payload == null) {
			return false;
		}

		PlayerBridge bridge = bridge(player.getUUID());

		if (!bridge.ready) {
			enqueue(bridge, payload);
			return false;
		}

		if (!bridge.queue.isEmpty()) {
			enqueue(bridge, payload);
			return flushQueue(player, bridge);
		}

		if (send(player, payload)) {
			return true;
		}

		enqueue(bridge, payload);
		return false;
	}

	@Override
	public void refresh(ServerPlayer player) {
		if (player == null) {
			return;
		}

		PlayerBridge bridge = players.get(player.getUUID());

		if (bridge == null || !bridge.ready) {
			return;
		}

		flushQueue(player, bridge);
		pushRequestedPlaceholders(player, bridge);
		pushRelationalPlaceholders(player, bridge);
		syncPlayerState(player, bridge);
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		PlayerBridge bridge = playerId == null ? null : players.get(playerId);
		return bridge == null ? Set.of() : Set.copyOf(bridge.requested.keySet());
	}

	@Override
	public Optional<TabBridgePacket> latestPacket(UUID playerId) {
		PlayerBridge bridge = playerId == null ? null : players.get(playerId);
		return bridge == null ? Optional.empty() : Optional.ofNullable(bridge.latestPacket);
	}

	@Override
	public void removePlayer(UUID playerId) {
		if (playerId != null) {
			players.remove(playerId);
		}
	}

	@Override
	public void close() {
		bus.unregisterIncoming(TAB_BRIDGE_CHANNEL);
		bus.unregisterOutgoing(TAB_BRIDGE_CHANNEL);
		players.clear();
	}

	@Override
	public List<String> describe(UUID playerId, String playerName) {
		List<String> lines = new ArrayList<>();
		PlayerBridge bridge = playerId == null ? null : players.get(playerId);

		if (bridge == null) {
			lines.add("  (tab bridge has no state for " + playerName + ")");
			return lines;
		}

		long now = System.currentTimeMillis();
		String packetAge = bridge.latestPacket == null ? "never" : (now - bridge.latestPacket.receivedAtEpochMillis()) + "ms ago";
		lines.add("── tab bridge · " + playerName + " · ready=" + bridge.ready
			+ " · warmup=" + (bridge.warmupUntil > now) + " · queue=" + bridge.queue.size()
			+ " · dropped=" + bridge.dropped + " · last frame from TAB " + packetAge + " ──");
		lines.add("  requested " + bridge.requested.size() + " placeholder(s), expansion values " + bridge.expansionValues.size());

		for (Map.Entry<String, Requested> entry : bridge.requested.entrySet()) {
			Requested state = entry.getValue();
			String lastSent = state.lastSentValue == null ? "(never sent)" : "\"" + preview(state.lastSentValue) + "\"";
			String sentAgo = state.lastSentAt == 0L ? "" : " " + (now - state.lastSentAt) + "ms ago";
			lines.add(String.format(Locale.ROOT, "  %s  every %sms  sent ×%d%s  = %s",
				entry.getKey(),
				state.refreshMillis == -1 ? "∞" : String.valueOf(state.refreshMillis),
				state.sends,
				sentAgo,
				lastSent));
		}

		return lines;
	}

	@Override
	public List<String> summary() {
		List<String> lines = new ArrayList<>();
		lines.add("── tab bridge · " + players.size() + " player(s) · received " + framesReceived.sum()
			+ " · sent " + framesSent.sum() + " (" + placeholderUpdatesSent.sum() + " placeholder updates) · dropped " + framesDropped.sum() + " ──");

		if (!rejectedIdentifiers.isEmpty()) {
			lines.add("  rejected identifiers:");

			for (Map.Entry<String, String> entry : rejectedIdentifiers.entrySet()) {
				lines.add("    " + preview(entry.getKey()) + " · " + entry.getValue());
			}
		}

		if (!unmatchedIdentifiers.isEmpty()) {
			lines.add("  requested but claimed by nothing (echoed back raw): " + unmatchedIdentifiers);
		}

		return lines;
	}

	@Override
	public boolean trace(UUID playerId, long seconds) {
		PlayerBridge bridge = playerId == null ? null : players.get(playerId);

		if (bridge == null) {
			return false;
		}

		bridge.traceUntil = System.currentTimeMillis() + Math.max(1L, seconds) * 1000L;
		return true;
	}

	// ----------------------------------------------------------------- inbound

	private void handleIncoming(ServerPlayer player, PlayerBridge bridge, byte[] payload) {
		if (payload == null || payload.length == 0) {
			return;
		}

		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			String action = input.readUTF();

			switch (action) {
				case "PlayerJoin" -> handlePlayerJoin(player, bridge, input);
				case "Expansion" -> handleExpansionUpdate(player, bridge, input);
				case "Placeholder" -> handlePlaceholderRegistration(player, bridge, input);
				case "Permission" -> handlePermissionRequest(player, input);
				case "Unload" -> players.remove(player.getUUID());
				default -> logger.debug("Bỏ qua TAB bridge action chưa hỗ trợ: " + action);
			}
		} catch (IOException exception) {
			logger.debug("Không thể giải mã TAB bridge payload cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void handlePlayerJoin(ServerPlayer player, PlayerBridge bridge, DataInputStream input) throws IOException {
		input.readInt();
		boolean forwardGroup = input.readBoolean();
		Map<String, Requested> requested = readPlaceholderRegistrations(player, input);
		readReplacementRules(input);

		if (input.available() > 0) {
			input.readBoolean();
		}

		bridge.queue.clear();
		bridge.requested.clear();
		bridge.requested.putAll(requested);
		bridge.warmupUntil = System.currentTimeMillis() + INITIAL_PLACEHOLDER_WARMUP_MILLIS;
		sendPlayerJoinResponse(player, bridge, forwardGroup);
		PlayerBridgeState current = captureState(player);
		sendInitialStatePackets(player, current);
		bridge.state = current;
	}

	private void handlePlaceholderRegistration(ServerPlayer player, PlayerBridge bridge, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		warnIfSuspiciousRequestedPlaceholder(player, "Placeholder", identifier);
		int refreshMillis = MIN_REFRESH_MILLIS;

		if (input.available() >= Integer.BYTES) {
			refreshMillis = input.readInt();
		}

		if (!accept(identifier)) {
			return;
		}

		bridge.requested.put(identifier, new Requested(normalizeRefresh(refreshMillis)));
		bridge.warmupUntil = System.currentTimeMillis() + INITIAL_PLACEHOLDER_WARMUP_MILLIS;

		if (identifier.startsWith(RELATIONAL_PREFIX)) {
			Map<String, String> byTarget = bridge.relational.get(identifier);
			sendRelationalUpdates(player, bridge, identifier, byTarget, Map.of());
			return;
		}

		String value = resolve(player, bridge, identifier);
		sendPlaceholderUpdate(player, bridge, identifier, value);
	}

	private void handlePermissionRequest(ServerPlayer player, DataInputStream input) throws IOException {
		String permission = input.readUTF();
		boolean granted = permissionService != null && permission != null && !permission.isBlank()
			&& permissionService.hasPermission(player.getUUID(), permission);

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(HAS_PERMISSION_ID);
			output.writeUTF(permission);
			output.writeBoolean(granted);
			sendRaw(player, bytes.toByteArray());
		}
	}

	private void handleExpansionUpdate(ServerPlayer player, PlayerBridge bridge, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		String value = input.readUTF();
		warnIfSuspiciousExpansionUpdate(player, identifier, value);

		if (identifier == null || identifier.isBlank()) {
			return;
		}

		bridge.expansionValues.put(normalizeKey(identifier), value == null ? "" : value);
	}

	private Map<String, Requested> readPlaceholderRegistrations(ServerPlayer player, DataInputStream input) throws IOException {
		int count = input.readInt();
		Map<String, Requested> requested = new LinkedHashMap<>();

		for (int index = 0; index < count; index++) {
			String identifier = input.readUTF();
			warnIfSuspiciousRequestedPlaceholder(player, "PlayerJoin", identifier);
			int refreshMillis = input.readInt();

			if (accept(identifier)) {
				requested.put(identifier, new Requested(normalizeRefresh(refreshMillis)));
			}
		}

		return requested;
	}

	private void readReplacementRules(DataInputStream input) throws IOException {
		int replacementCount = input.readInt();

		for (int index = 0; index < replacementCount; index++) {
			input.readUTF();
			int ruleCount = input.readInt();

			for (int rule = 0; rule < ruleCount; rule++) {
				input.readUTF();
				input.readUTF();
			}
		}
	}

	// ---------------------------------------------------------------- outbound

	private void sendPlayerJoinResponse(ServerPlayer player, PlayerBridge bridge, boolean forwardGroup) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(PLAYER_JOIN_RESPONSE_ID);
			output.writeUTF(worldOf(player));

			if (forwardGroup) {
				output.writeUTF(groupOf(player));
			}

			output.writeInt(bridge.requested.size());

			for (Map.Entry<String, Requested> entry : bridge.requested.entrySet()) {
				String identifier = entry.getKey();
				output.writeUTF(identifier);

				if (identifier.startsWith(RELATIONAL_PREFIX)) {
					writeRelationalMap(output, bridge.relational.get(identifier));
					continue;
				}

				String value = resolve(player, bridge, identifier);
				output.writeUTF(value);
				entry.getValue().remember(value);
			}

			output.writeInt(gameModeOf(player));
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB PlayerJoinResponse cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void pushRequestedPlaceholders(ServerPlayer player, PlayerBridge bridge) {
		boolean warmup = bridge.warmupUntil > System.currentTimeMillis();

		for (Map.Entry<String, Requested> entry : bridge.requested.entrySet()) {
			String identifier = entry.getKey();

			if (identifier.startsWith(RELATIONAL_PREFIX)) {
				continue;
			}

			Requested state = entry.getValue();

			// TAB on the proxy drops updates that arrive before it has loaded the player, so a
			// slow placeholder (host name, refresh 10s or never) sent once at join can be lost for
			// good. Through the warmup, re-send everything at least once a second.
			boolean warmupResend = warmup && System.currentTimeMillis() - state.lastSentAt >= WARMUP_RESEND_MILLIS;

			if (!state.due() && !warmupResend) {
				continue;
			}

			String value = resolve(player, bridge, identifier);

			if (warmup || !Objects.equals(state.lastSentValue, value)) {
				sendPlaceholderUpdate(player, bridge, identifier, value);
			}

			state.advance();
		}
	}

	private void pushRelationalPlaceholders(ServerPlayer player, PlayerBridge bridge) {
		boolean anyRelational = false;

		for (String identifier : bridge.requested.keySet()) {
			if (identifier.startsWith(RELATIONAL_PREFIX)) {
				anyRelational = true;
				break;
			}
		}

		if (!anyRelational) {
			return;
		}

		Map<String, Map<String, String>> current = relationalSource.resolve(player);
		Map<String, Map<String, String>> safeCurrent = current == null ? Map.of() : current;

		for (Map.Entry<String, Requested> entry : bridge.requested.entrySet()) {
			String identifier = entry.getKey();

			if (!identifier.startsWith(RELATIONAL_PREFIX)) {
				continue;
			}

			Requested state = entry.getValue();

			if (!state.due()) {
				continue;
			}

			Map<String, String> byTarget = safeCurrent.getOrDefault(identifier, Map.of());
			Map<String, String> lastSent = bridge.lastSentRelational.getOrDefault(identifier, Map.of());
			sendRelationalUpdates(player, bridge, identifier, byTarget, lastSent);

			for (String target : lastSent.keySet()) {
				if (!byTarget.containsKey(target)) {
					sendRelationalUpdate(player, bridge, identifier, target, "");
				}
			}

			bridge.lastSentRelational.put(identifier, Map.copyOf(byTarget));
			state.advance();
		}

		bridge.relational.clear();
		bridge.relational.putAll(safeCurrent);
	}

	private void sendPlaceholderUpdate(ServerPlayer player, PlayerBridge bridge, String identifier, String value) {
		String safeValue = value == null ? "" : value;

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_PLACEHOLDER_ID);
			output.writeUTF(identifier);
			output.writeUTF(safeValue);
			sendRaw(player, bytes.toByteArray());
			placeholderUpdatesSent.increment();

			Requested state = bridge.requested.get(identifier);

			if (state != null) {
				state.remember(safeValue);
			}

			if (bridge.traceUntil > System.currentTimeMillis()) {
				logger.info("[trace " + nameOf(player) + "] " + identifier + " = " + preview(safeValue));
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder " + identifier + " cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void sendRelationalUpdates(ServerPlayer player, PlayerBridge bridge, String identifier, Map<String, String> byTarget, Map<String, String> previous) {
		if (byTarget == null || byTarget.isEmpty()) {
			return;
		}

		for (Map.Entry<String, String> entry : byTarget.entrySet()) {
			String value = entry.getValue() == null ? "" : entry.getValue();

			if (value.equals(previous.get(entry.getKey()))) {
				continue;
			}

			sendRelationalUpdate(player, bridge, identifier, entry.getKey(), value);
		}
	}

	private void sendRelationalUpdate(ServerPlayer player, PlayerBridge bridge, String identifier, String target, String value) {
		if (target == null || target.isBlank()) {
			return;
		}

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(UPDATE_PLACEHOLDER_ID);
			output.writeUTF(identifier);
			output.writeUTF(target);
			output.writeUTF(value == null ? "" : value);
			sendRaw(player, bytes.toByteArray());
			placeholderUpdatesSent.increment();

			if (bridge.traceUntil > System.currentTimeMillis()) {
				logger.info("[trace " + nameOf(player) + "] " + identifier + " → " + target + " = " + preview(value));
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder quan hệ " + identifier + " cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void writeRelationalMap(DataOutputStream output, Map<String, String> byTarget) throws IOException {
		if (byTarget == null || byTarget.isEmpty()) {
			output.writeInt(0);
			return;
		}

		output.writeInt(byTarget.size());

		for (Map.Entry<String, String> entry : byTarget.entrySet()) {
			output.writeUTF(entry.getKey());
			output.writeUTF(entry.getValue() == null ? "" : entry.getValue());
		}
	}

	private void syncPlayerState(ServerPlayer player, PlayerBridge bridge) {
		PlayerBridgeState current = captureState(player);
		PlayerBridgeState previous = bridge.state;
		bridge.state = current;

		if (previous == null) {
			return;
		}

		if (!previous.worldName().equals(current.worldName())) {
			sendUtf(player, SET_WORLD_ID, current.worldName());
		}

		if (!previous.groupName().equals(current.groupName())) {
			sendUtf(player, SET_GROUP_ID, current.groupName());
		}

		if (previous.gameModeId() != current.gameModeId()) {
			sendInt(player, UPDATE_GAME_MODE_ID, current.gameModeId());
		}

		if (previous.invisible() != current.invisible()) {
			sendBoolean(player, INVISIBLE_ID, current.invisible());
		}

		if (previous.disguised() != current.disguised()) {
			sendBoolean(player, DISGUISED_ID, current.disguised());
		}

		if (previous.vanished() != current.vanished()) {
			sendBoolean(player, VANISHED_ID, current.vanished());
		}
	}

	private void sendInitialStatePackets(ServerPlayer player, PlayerBridgeState state) {
		if (state.invisible()) {
			sendBoolean(player, INVISIBLE_ID, true);
		}

		if (state.disguised()) {
			sendBoolean(player, DISGUISED_ID, true);
		}

		if (state.vanished()) {
			sendBoolean(player, VANISHED_ID, true);
		}
	}

	private void sendUtf(ServerPlayer player, byte id, String value) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(id);
			output.writeUTF(value == null ? "" : value);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB frame " + id + " cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void sendInt(ServerPlayer player, byte id, int value) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(id);
			output.writeInt(value);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB frame " + id + " cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void sendBoolean(ServerPlayer player, byte id, boolean value) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeByte(id);
			output.writeBoolean(value);
			sendRaw(player, bytes.toByteArray());
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB frame " + id + " cho " + nameOf(player) + ": " + exception.getMessage());
		}
	}

	// --------------------------------------------------------------- resolving

	/**
	 * The value TAB should see for an identifier: the engine's answer, else a
	 * value TAB itself pushed for that identifier, else the identifier echoed back
	 * so a typo stays visible in the tab list rather than vanishing.
	 */
	private String resolve(ServerPlayer player, PlayerBridge bridge, String identifier) {
		if (placeholders != null) {
			PlaceholderRef ref = PlaceholderGrammar.parse(identifier);
			Resolution<ServerPlayer> resolution = placeholders.engine().resolve(ref, player);

			if (resolution.hasValue()) {
				return resolution.value();
			}
		}

		String pushed = bridge.expansionValues.get(normalizeKey(identifier));

		if (pushed != null) {
			return pushed;
		}

		if (unmatchedIdentifiers.add(identifier)) {
			logger.info("TAB yêu cầu placeholder không có gì nhận: " + preview(identifier) + " (trả lại nguyên văn).");
		}

		return identifier;
	}

	private String worldOf(ServerPlayer player) {
		PlayerSample sample = placeholders == null ? null : placeholders.engine().viewer(player);

		if (sample != null && !sample.dimensionId().isBlank()) {
			return sample.dimensionId();
		}

		return "unknown";
	}

	private String groupOf(ServerPlayer player) {
		PlayerSample sample = placeholders == null ? null : placeholders.engine().viewer(player);

		if (sample != null && !sample.groupName().isBlank()) {
			return sample.groupName();
		}

		if (permissionService == null || !permissionService.isAvailable()) {
			return "";
		}

		String group = permissionService.getGroupName(player.getUUID());
		return group == null ? "" : group;
	}

	private int gameModeOf(ServerPlayer player) {
		try {
			return player.gameMode.getGameModeForPlayer().getId();
		} catch (RuntimeException exception) {
			return 0;
		}
	}

	private PlayerBridgeState captureState(ServerPlayer player) {
		TabBridgePlayerState external = playerStateSource.resolve(player);
		TabBridgePlayerState safeExternal = external == null ? TabBridgePlayerState.DEFAULT : external;
		return new PlayerBridgeState(worldOf(player), groupOf(player), gameModeOf(player), player.isInvisible(), safeExternal.disguised(), safeExternal.vanished());
	}

	// ------------------------------------------------------------------- queue

	private void enqueue(PlayerBridge bridge, byte[] payload) {
		synchronized (bridge.queue) {
			while (bridge.queue.size() >= MAX_QUEUED_FRAMES) {
				bridge.queue.pollFirst();
				bridge.dropped++;
				framesDropped.increment();
			}

			bridge.queue.addLast(payload.clone());
		}
	}

	private boolean flushQueue(ServerPlayer player, PlayerBridge bridge) {
		if (!bridge.ready) {
			return false;
		}

		while (true) {
			byte[] next;

			synchronized (bridge.queue) {
				next = bridge.queue.peekFirst();
			}

			if (next == null) {
				return true;
			}

			if (!send(player, next)) {
				return false;
			}

			synchronized (bridge.queue) {
				bridge.queue.pollFirst();
			}
		}
	}

	private boolean send(ServerPlayer player, byte[] payload) {
		boolean sent = bus.send(player, TAB_BRIDGE_CHANNEL, payload);

		if (sent) {
			framesSent.increment();
		}

		return sent;
	}

	// ------------------------------------------------------------- validation

	/**
	 * Whether TAB's requested identifier is one this side will track. Refusals
	 * are remembered with their reason so they can be listed instead of guessed.
	 */
	private boolean accept(String identifier) {
		String reason = rejectionReason(identifier);

		if (reason == null) {
			return true;
		}

		if (identifier != null && rejectedIdentifiers.putIfAbsent(identifier, reason) == null) {
			logger.warn("TAB bridge từ chối placeholder " + preview(identifier) + ": " + reason);
		}

		return false;
	}

	private String rejectionReason(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return "blank";
		}

		String trimmed = identifier.trim();

		if (trimmed.length() < 3 || !trimmed.startsWith("%") || !trimmed.endsWith("%")) {
			return "not wrapped in percent signs";
		}

		for (int index = 1; index < trimmed.length() - 1; index++) {
			char character = trimmed.charAt(index);

			if (character == '\n' || character == '\r' || Character.isISOControl(character)) {
				return "control character";
			}

			// a progress bar glyph inside an identifier means a value was mistaken
			// for a placeholder somewhere upstream
			if (Character.UnicodeBlock.of(character) == Character.UnicodeBlock.BLOCK_ELEMENTS) {
				return "block element glyph in identifier";
			}
		}

		return null;
	}

	private void warnIfSuspiciousRequestedPlaceholder(ServerPlayer player, String action, String identifier) {
		if (identifier != null && identifier.length() >= LARGE_PLACEHOLDER_IDENTIFIER_LENGTH) {
			logger.warn("TAB bridge request quá dài từ proxy: action=" + action + " player=" + nameOf(player)
				+ " length=" + identifier.length() + " placeholder=" + preview(identifier));
		}
	}

	private void warnIfSuspiciousExpansionUpdate(ServerPlayer player, String identifier, String value) {
		if (identifier != null && identifier.length() >= LARGE_PLACEHOLDER_IDENTIFIER_LENGTH) {
			logger.warn("TAB bridge Expansion gửi identifier quá dài: player=" + nameOf(player)
				+ " length=" + identifier.length() + " identifier=" + preview(identifier));
		}

		if (value != null && value.length() >= LARGE_PLACEHOLDER_VALUE_LENGTH) {
			logger.warn("TAB bridge Expansion gửi value quá dài: player=" + nameOf(player)
				+ " length=" + value.length() + " identifier=" + preview(identifier));
		}
	}

	private static int normalizeRefresh(int refreshMillis) {
		if (refreshMillis == -1) {
			return -1;
		}

		return Math.max(MIN_REFRESH_MILLIS, refreshMillis);
	}

	private static String normalizeKey(String identifier) {
		String normalized = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);

		if (normalized.length() >= 2 && normalized.startsWith("%") && normalized.endsWith("%")) {
			return normalized.substring(1, normalized.length() - 1);
		}

		return normalized;
	}

	private static String nameOf(ServerPlayer player) {
		return player == null ? "<unknown>" : player.getName().getString();
	}

	private static String preview(String value) {
		if (value == null) {
			return "<null>";
		}

		String sanitized = value.replace("\r", "\\r").replace("\n", "\\n");
		return sanitized.length() <= PLACEHOLDER_PREVIEW_LENGTH ? sanitized : sanitized.substring(0, PLACEHOLDER_PREVIEW_LENGTH) + "...";
	}

	private PlayerBridge bridge(UUID playerId) {
		return players.computeIfAbsent(playerId, ignored -> new PlayerBridge());
	}

	// ------------------------------------------------------------------ state

	/** Everything the bridge knows about one player. */
	private static final class PlayerBridge {
		final Map<String, Requested> requested = new ConcurrentHashMap<>();
		final Map<String, String> expansionValues = new ConcurrentHashMap<>();
		final Map<String, Map<String, String>> relational = new ConcurrentHashMap<>();
		final Map<String, Map<String, String>> lastSentRelational = new ConcurrentHashMap<>();
		final Deque<byte[]> queue = new ArrayDeque<>();
		volatile boolean ready;
		volatile long warmupUntil;
		volatile long traceUntil;
		volatile int dropped;
		volatile TabBridgePacket latestPacket;
		volatile PlayerBridgeState state;
	}

	/** One identifier TAB asked for, and what was last said about it. */
	private static final class Requested {
		final int refreshMillis;
		volatile long nextDueAt;
		volatile boolean initialPending = true;
		volatile String lastSentValue;
		volatile long lastSentAt;
		volatile int sends;

		Requested(int refreshMillis) {
			this.refreshMillis = refreshMillis;
		}

		boolean due() {
			if (initialPending) {
				return true;
			}

			return refreshMillis != -1 && System.currentTimeMillis() >= nextDueAt;
		}

		void advance() {
			initialPending = false;
			nextDueAt = refreshMillis == -1 ? Long.MAX_VALUE : System.currentTimeMillis() + refreshMillis;
		}

		void remember(String value) {
			lastSentValue = value;
			lastSentAt = System.currentTimeMillis();
			sends++;
		}
	}

	private record PlayerBridgeState(String worldName, String groupName, int gameModeId, boolean invisible, boolean disguised, boolean vanished) {
		private PlayerBridgeState {
			worldName = worldName == null ? "unknown" : worldName;
			groupName = groupName == null ? "" : groupName;
		}
	}
}
