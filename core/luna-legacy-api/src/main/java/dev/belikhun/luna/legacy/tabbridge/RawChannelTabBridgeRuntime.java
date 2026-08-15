package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.messaging.bus.PluginMessagingBus;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.string.Strings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TAB's bridge protocol, spoken over whatever channel the platform can carry.
 *
 * The protocol is a byte discriminator and then `DataOutputStream` fields, in
 * both directions. TAB sends five actions by name and reads nine by id, and the
 * ids are positional - there is no length prefix and no version negotiation past
 * the channel name itself, so a field written in the wrong order is not an error
 * anywhere, just a tab list that renders nonsense.
 *
 * **Three behaviours here are not obvious and all three are load-bearing.**
 *
 * *Queueing.* A payload sent before TAB has said `PlayerJoin` is dropped by the
 * proxy with no error, so nothing is sent until the player is marked ready and
 * anything produced before that waits in a per-player queue.
 *
 * *Warmup.* For the first ten seconds after a join, values are sent even when
 * they have not changed. The change filter is what keeps this cheap in the
 * steady state, but on a join the "previous" value is whatever a half-populated
 * snapshot happened to hold, and filtering against it leaves a permanently blank
 * cell that nothing will ever refresh.
 *
 * *Percent escaping.* An identifier ending `_safe` promises TAB that its value
 * contains no placeholder syntax, so a `%` in the value is doubled on the way
 * out. Without it a balance of `50%` becomes a placeholder TAB then tries to
 * resolve.
 */
public final class RawChannelTabBridgeRuntime<P> implements TabBridgeRuntime<P> {
	private static final long INITIAL_PLACEHOLDER_WARMUP_MILLIS = 10_000L;
	private static final int LARGE_PLACEHOLDER_IDENTIFIER_LENGTH = 256;
	private static final int LARGE_PLACEHOLDER_VALUE_LENGTH = 512;
	private static final int PLACEHOLDER_PREVIEW_LENGTH = 160;
	private static final int MINIMUM_REFRESH_MILLIS = 50;
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
	private final PluginMessagingBus<P> bus;
	private final TabPlayerBridge<P> players;
	private final PermissionService permissions;
	private final TabBridgePlayerStateSource<P> playerStateSource;

	private final Map<UUID, Map<String, String>> placeholdersByPlayer;
	private final Map<UUID, Map<String, Map<String, String>>> relationalPlaceholdersByPlayer;
	private final Map<UUID, PlayerBridgeState> stateByPlayer;
	private final Map<UUID, TabBridgePacket> packetsByPlayer;
	private final Map<UUID, Deque<byte[]>> queuedOutgoingPayloadsByPlayer;
	private final Map<UUID, Map<String, RequestedPlaceholderState>> requestedPlaceholdersByPlayer;
	private final Map<UUID, Map<String, String>> lastSentPlaceholderValuesByPlayer;
	private final Map<UUID, Map<String, Map<String, String>>> lastSentRelationalPlaceholderValuesByPlayer;
	private final Set<UUID> readyPlayers;
	private final Map<UUID, Long> placeholderWarmupUntilByPlayer;

	private volatile TabBridgePlaceholderResolver<P> placeholderResolver;

	public RawChannelTabBridgeRuntime(
		LunaLogger logger,
		PluginMessagingBus<P> bus,
		TabPlayerBridge<P> players,
		PermissionService permissions,
		TabBridgePlayerStateSource<P> playerStateSource
	) {
		this.logger = logger.scope("Runtime");
		this.bus = Objects.requireNonNull(bus, "bus");
		this.players = Objects.requireNonNull(players, "players");
		this.permissions = permissions;
		this.playerStateSource = playerStateSource == null
			? new NoopTabBridgePlayerStateSource<P>()
			: playerStateSource;

		this.placeholdersByPlayer = new ConcurrentHashMap<UUID, Map<String, String>>();
		this.relationalPlaceholdersByPlayer = new ConcurrentHashMap<UUID, Map<String, Map<String, String>>>();
		this.stateByPlayer = new ConcurrentHashMap<UUID, PlayerBridgeState>();
		this.packetsByPlayer = new ConcurrentHashMap<UUID, TabBridgePacket>();
		this.queuedOutgoingPayloadsByPlayer = new ConcurrentHashMap<UUID, Deque<byte[]>>();
		this.requestedPlaceholdersByPlayer = new ConcurrentHashMap<UUID, Map<String, RequestedPlaceholderState>>();
		this.lastSentPlaceholderValuesByPlayer = new ConcurrentHashMap<UUID, Map<String, String>>();
		this.lastSentRelationalPlaceholderValuesByPlayer = new ConcurrentHashMap<UUID, Map<String, Map<String, String>>>();
		this.readyPlayers = ConcurrentHashMap.newKeySet();
		this.placeholderWarmupUntilByPlayer = new ConcurrentHashMap<UUID, Long>();
		this.placeholderResolver = null;

		this.bus.registerOutgoing(TabBridgeChannels.BRIDGE);
		this.bus.registerIncoming(TabBridgeChannels.BRIDGE, context -> {
			P source = context.source();

			if (source == null) {
				return PluginMessageDispatchResult.HANDLED;
			}

			UUID sourceId = players.idOf(source);

			packetsByPlayer.put(sourceId, new TabBridgePacket(
				sourceId,
				players.nameOf(source),
				context.payload(),
				System.currentTimeMillis()
			));

			markPlayerReady(sourceId);
			handleIncoming(source, context.payload());
			flushQueuedMessages(source);

			return PluginMessageDispatchResult.HANDLED;
		});
	}

	@Override
	public void bindPlaceholderResolver(TabBridgePlaceholderResolver<P> placeholderResolver) {
		this.placeholderResolver = placeholderResolver;
	}

	@Override
	public boolean sendRaw(P player, byte[] payload) {
		if (player == null || payload == null) {
			return false;
		}

		UUID playerId = players.idOf(player);

		if (!isPlayerReady(playerId)) {
			enqueuePayload(playerId, payload);

			return false;
		}

		// anything already waiting goes first, or a later payload would overtake an
		// earlier one and TAB would apply them out of order
		Deque<byte[]> queuedPayloads = queuedOutgoingPayloadsByPlayer.get(playerId);

		if (queuedPayloads != null && !queuedPayloads.isEmpty()) {
			enqueuePayload(playerId, payload);

			return flushQueuedMessages(player);
		}

		if (bus.send(player, TabBridgeChannels.BRIDGE, payload)) {
			return true;
		}

		enqueuePayload(playerId, payload);

		return false;
	}

	@Override
	public void updatePlayerPlaceholders(P player, Map<String, String> placeholderValues) {
		if (player == null) {
			return;
		}

		UUID playerId = players.idOf(player);
		Map<String, String> safeValues = new LinkedHashMap<String, String>();

		if (placeholderValues != null) {
			safeValues.putAll(placeholderValues);
		}

		Map<String, String> mergedValues = mergePlaceholderSnapshot(playerId, safeValues);

		flushQueuedMessages(player);
		pushRequestedPlaceholderUpdates(player, mergedValues);
		syncPlayerState(player);
	}

	@Override
	public void updatePlayerRelationalPlaceholders(P player, Map<String, Map<String, String>> placeholderValues) {
		if (player == null) {
			return;
		}

		UUID playerId = players.idOf(player);
		Map<String, Map<String, String>> safeValues = new LinkedHashMap<String, Map<String, String>>();

		if (placeholderValues != null) {
			for (Map.Entry<String, Map<String, String>> entry : placeholderValues.entrySet()) {
				String identifier = entry.getKey();

				if (Strings.isBlank(identifier)) {
					continue;
				}

				safeValues.put(identifier, copyTargetValues(entry.getValue()));
			}
		}

		Map<String, Map<String, String>> previousValues = relationalPlaceholdersByPlayer.put(
			playerId,
			Collections.unmodifiableMap(safeValues)
		);

		flushQueuedMessages(player);
		pushRequestedRelationalPlaceholderUpdates(player, previousValues, safeValues);
	}

	@Override
	public Map<String, String> placeholderValues(UUID playerId) {
		if (playerId == null) {
			return Collections.emptyMap();
		}

		Map<String, String> values = placeholdersByPlayer.get(playerId);

		return values == null ? Collections.<String, String>emptyMap() : values;
	}

	@Override
	public Set<String> requestedPlaceholderIdentifiers(UUID playerId) {
		if (playerId == null) {
			return Collections.emptySet();
		}

		Map<String, RequestedPlaceholderState> registrations = requestedPlaceholdersByPlayer.get(playerId);

		if (registrations == null || registrations.isEmpty()) {
			return Collections.emptySet();
		}

		return Collections.unmodifiableSet(new LinkedHashSet<String>(registrations.keySet()));
	}

	@Override
	public TabBridgePacket latestPacket(UUID playerId) {
		return playerId == null ? null : packetsByPlayer.get(playerId);
	}

	@Override
	public void removePlayer(UUID playerId) {
		clearPlayerState(playerId);
	}

	@Override
	public void close() {
		bus.unregisterIncoming(TabBridgeChannels.BRIDGE);
		bus.unregisterOutgoing(TabBridgeChannels.BRIDGE);

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

	private void handleIncoming(P player, byte[] payload) {
		if (payload == null || payload.length == 0) {
			return;
		}

		try {
			DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));

			try {
				String action = input.readUTF();

				if ("PlayerJoin".equals(action)) {
					handlePlayerJoin(player, input);
				} else if ("Expansion".equals(action)) {
					handleExpansionUpdate(player, input);
				} else if ("Placeholder".equals(action)) {
					handlePlaceholderRegistration(player, input);
				} else if ("Permission".equals(action)) {
					handlePermissionRequest(player, input);
				} else if ("Unload".equals(action)) {
					clearPlayerState(players.idOf(player));
				} else {
					logger.debug("Bỏ qua TAB bridge action chưa hỗ trợ: " + action);
				}
			} finally {
				input.close();
			}
		} catch (IOException exception) {
			logger.debug("Không thể giải mã TAB bridge payload cho " + players.nameOf(player)
				+ ": " + exception.getMessage());
		}
	}

	/**
	 * TAB's opening frame, and the only one carrying its whole request at once.
	 *
	 * The trailing `readBoolean` is guarded by `available()` because TAB added that
	 * field within the protocol version: a proxy on an older build of the same
	 * version sends the frame without it, and reading it unconditionally throws
	 * `EOFException` on every join.
	 */
	private void handlePlayerJoin(P player, DataInputStream input) throws IOException {
		input.readInt();

		boolean forwardGroup = input.readBoolean();
		Map<String, RequestedPlaceholderState> requestedPlaceholders = readPlaceholderRegistrations(player, input);

		readReplacementRules(input);

		if (input.available() > 0) {
			input.readBoolean();
		}

		UUID playerId = players.idOf(player);

		// a rejoin has to start clean: anything queued belongs to the session that
		// just ended and TAB has already forgotten it
		clearQueuedMessages(playerId);

		requestedPlaceholdersByPlayer.put(playerId, requestedPlaceholders);
		startPlaceholderWarmup(playerId);
		sendPlayerJoinResponse(player, forwardGroup, requestedPlaceholders);

		PlayerBridgeState currentState = captureState(player);

		sendInitialStatePackets(player, currentState);
		stateByPlayer.put(playerId, currentState);
	}

	private void handlePlaceholderRegistration(P player, DataInputStream input) throws IOException {
		String identifier = input.readUTF();

		warnIfSuspiciousRequestedPlaceholder(player, "Placeholder", identifier);

		int refreshMillis = MINIMUM_REFRESH_MILLIS;

		if (input.available() >= Integer.BYTES) {
			refreshMillis = input.readInt();
		}

		if (!isSupportedRequestedPlaceholderIdentifier(identifier)) {
			return;
		}

		UUID playerId = players.idOf(player);

		registerPlaceholder(playerId, identifier, refreshMillis);
		startPlaceholderWarmup(playerId);

		if (isRelational(identifier)) {
			Map<String, Map<String, String>> relational = relationalPlaceholdersByPlayer.get(playerId);
			Map<String, String> valuesByTarget = relational == null ? null : relational.get(identifier);

			sendRelationalPlaceholderUpdates(player, identifier, valuesByTarget, Collections.<String, String>emptyMap());

			return;
		}

		Map<String, String> snapshot = placeholderValues(playerId);
		String resolvedValue = resolvePlaceholderValue(player, identifier, snapshot);

		sendSinglePlaceholderUpdate(player, identifier, resolvedValue);
		rememberSentPlaceholderValue(playerId, identifier, resolvedValue);
	}

	private void handlePermissionRequest(P player, DataInputStream input) throws IOException {
		String permission = input.readUTF();

		sendPermissionResponse(player, permission, resolvePermission(player, permission));
	}

	private void handleExpansionUpdate(P player, DataInputStream input) throws IOException {
		String identifier = input.readUTF();
		String value = input.readUTF();

		warnIfSuspiciousExpansionUpdate(player, identifier, value);

		if (Strings.isBlank(identifier)) {
			return;
		}

		mergePlaceholderSnapshot(
			players.idOf(player),
			Collections.singletonMap(identifier, value == null ? "" : value)
		);
	}

	/**
	 * Merge into the player's snapshot, keying each value twice.
	 *
	 * TAB asks with the percent signs on (`%luna_tps%`) and the placeholder service
	 * answers with them off (`luna_tps`), and both spellings reach this map from
	 * different directions. Storing both is what lets a lookup be a map read rather
	 * than a normalisation on every hit, and there are hundreds of hits per second.
	 */
	private Map<String, String> mergePlaceholderSnapshot(UUID playerId, Map<String, String> incomingValues) {
		if (playerId == null) {
			return Collections.emptyMap();
		}

		Map<String, String> merged = placeholdersByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, String> updated = existing == null
				? new ConcurrentHashMap<String, String>()
				: existing;

			if (incomingValues != null) {
				for (Map.Entry<String, String> entry : incomingValues.entrySet()) {
					String identifier = entry.getKey();

					if (Strings.isBlank(identifier)) {
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

		return merged == null ? Collections.<String, String>emptyMap() : merged;
	}

	private Map<String, RequestedPlaceholderState> readPlaceholderRegistrations(
		P player,
		DataInputStream input
	) throws IOException {
		int placeholderCount = input.readInt();
		Map<String, RequestedPlaceholderState> placeholders = new LinkedHashMap<String, RequestedPlaceholderState>();

		for (int index = 0; index < placeholderCount; index += 1) {
			String identifier = input.readUTF();

			warnIfSuspiciousRequestedPlaceholder(player, "PlayerJoin", identifier);

			int refreshMillis = input.readInt();

			// still read both fields before skipping it, or the stream desynchronises
			// and every later field in the frame is garbage
			if (!isSupportedRequestedPlaceholderIdentifier(identifier)) {
				continue;
			}

			placeholders.put(identifier, new RequestedPlaceholderState(normalizeRefreshMillis(refreshMillis)));
		}

		return placeholders;
	}

	/** Read past TAB's replacement rules: the proxy applies them, the backend never does. */
	private void readReplacementRules(DataInputStream input) throws IOException {
		int replacementCount = input.readInt();

		for (int index = 0; index < replacementCount; index += 1) {
			input.readUTF();

			int ruleCount = input.readInt();

			for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex += 1) {
				input.readUTF();
				input.readUTF();
			}
		}
	}

	private void sendPlayerJoinResponse(
		P player,
		boolean forwardGroup,
		Map<String, RequestedPlaceholderState> requestedPlaceholders
	) {
		UUID playerId = players.idOf(player);
		Map<String, String> snapshot = placeholderValues(playerId);
		Map<String, Map<String, String>> relationalSnapshot = relationalPlaceholdersByPlayer.get(playerId);

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(PLAYER_JOIN_RESPONSE_ID);
				output.writeUTF(players.worldName(player));

				// only when TAB asked for it: the field is absent otherwise, not empty
				if (forwardGroup) {
					output.writeUTF(resolveGroupName(player));
				}

				output.writeInt(requestedPlaceholders.size());

				for (String identifier : requestedPlaceholders.keySet()) {
					output.writeUTF(identifier);

					if (isRelational(identifier)) {
						Map<String, String> valuesByTarget = relationalSnapshot == null
							? null
							: relationalSnapshot.get(identifier);

						writeRelationalPlaceholderMap(output, valuesByTarget);
					} else {
						output.writeUTF(resolvePlaceholderValue(player, identifier, snapshot));
					}
				}

				output.writeInt(players.gameModeId(player));
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB PlayerJoinResponse cho " + players.nameOf(player)
				+ ": " + exception.getMessage());
		}
	}

	private void pushRequestedPlaceholderUpdates(P player, Map<String, String> placeholderValues) {
		UUID playerId = players.idOf(player);
		Map<String, RequestedPlaceholderState> requestedPlaceholders = requestedPlaceholdersByPlayer.get(playerId);

		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		boolean warmupActive = isPlaceholderWarmupActive(playerId);

		for (Map.Entry<String, RequestedPlaceholderState> entry : requestedPlaceholders.entrySet()) {
			String identifier = entry.getKey();

			if (isRelational(identifier)) {
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

			sendSinglePlaceholderUpdate(player, identifier, value);
			rememberSentPlaceholderValue(playerId, identifier, value);
			markPlaceholderEvaluated(playerId, identifier);
		}
	}

	private void pushRequestedRelationalPlaceholderUpdates(
		P player,
		Map<String, Map<String, String>> previousValues,
		Map<String, Map<String, String>> placeholderValues
	) {
		UUID playerId = players.idOf(player);
		Map<String, RequestedPlaceholderState> requestedPlaceholders = requestedPlaceholdersByPlayer.get(playerId);

		if (requestedPlaceholders == null || requestedPlaceholders.isEmpty()) {
			return;
		}

		Map<String, Map<String, String>> lastSent = lastSentRelationalPlaceholderValuesByPlayer.get(playerId);

		for (Map.Entry<String, RequestedPlaceholderState> entry : requestedPlaceholders.entrySet()) {
			String identifier = entry.getKey();

			if (!isRelational(identifier)) {
				continue;
			}

			RequestedPlaceholderState state = entry.getValue();

			if (state == null || !state.shouldEvaluate()) {
				continue;
			}

			Map<String, String> currentValuesByTarget = placeholderValues == null
				? null
				: placeholderValues.get(identifier);

			Map<String, String> lastSentValuesByTarget = lastSent == null
				? Collections.<String, String>emptyMap()
				: orEmpty(lastSent.get(identifier));

			sendRelationalPlaceholderUpdates(player, identifier, currentValuesByTarget, lastSentValuesByTarget);
			sendClearedRelationalPlaceholderUpdates(player, identifier, lastSentValuesByTarget, currentValuesByTarget);
			rememberSentRelationalPlaceholderValues(playerId, identifier, currentValuesByTarget);
			markPlaceholderEvaluated(playerId, identifier);
		}
	}

	private void sendSinglePlaceholderUpdate(P player, String identifier, String resolvedValue) {
		if (player == null || Strings.isBlank(identifier) || isRelational(identifier)) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(UPDATE_PLACEHOLDER_ID);
				output.writeUTF(identifier);
				output.writeUTF(resolvedValue == null ? "" : resolvedValue);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder " + identifier + " cho " + players.nameOf(player)
				+ ": " + exception.getMessage());
		}
	}

	/**
	 * Snapshot first, live resolver second - except for `_safe`, which is inverted.
	 *
	 * The snapshot is a tick old and free; the resolver is current and costs a
	 * lookup, so the ordinary path prefers the snapshot. A `_safe` identifier is
	 * asked live because that suffix exists for the expensive values (the CPU
	 * gauges), whose whole point is to be sampled rather than remembered.
	 */
	private String resolvePlaceholderValue(P player, String identifier, Map<String, String> placeholderValues) {
		if (shouldPreferResolver(identifier)) {
			String resolved = resolveLive(player, identifier);

			if (resolved != null) {
				return normalizeBridgePlaceholderValue(identifier, resolved);
			}
		}

		if (hasSnapshotValue(identifier, placeholderValues)) {
			return normalizeBridgePlaceholderValue(identifier, resolveSnapshotValue(identifier, placeholderValues));
		}

		String resolved = resolveLive(player, identifier);

		if (resolved != null) {
			return normalizeBridgePlaceholderValue(identifier, resolved);
		}

		return normalizeBridgePlaceholderValue(identifier, resolveSnapshotValue(identifier, placeholderValues));
	}

	private String resolveLive(P player, String identifier) {
		TabBridgePlaceholderResolver<P> resolver = placeholderResolver;

		if (resolver == null || player == null) {
			return null;
		}

		return resolver.resolve(player, identifier);
	}

	private boolean shouldPreferResolver(String identifier) {
		if (Strings.isBlank(identifier)) {
			return false;
		}

		String normalized = normalizeSnapshotLookupKey(identifier);

		return normalized.startsWith("luna_") && normalized.endsWith("_safe");
	}

	/**
	 * Whether an identifier is one this backend will answer at all.
	 *
	 * TAB registers whatever its config names, including identifiers meant for a
	 * different expansion entirely, and a control character or a block-drawing glyph
	 * in one is a sign the config is feeding it rendered output rather than a name.
	 * Refusing those keeps a malformed config from becoming a per-tick resolve of
	 * something that can never have a value.
	 */
	private boolean isSupportedRequestedPlaceholderIdentifier(String identifier) {
		if (Strings.isBlank(identifier)) {
			return false;
		}

		String trimmed = identifier.trim();

		if (trimmed.length() < 3 || !trimmed.startsWith("%") || !trimmed.endsWith("%")) {
			return false;
		}

		String inner = trimmed.substring(1, trimmed.length() - 1);

		if (Strings.isBlank(inner)) {
			return false;
		}

		for (int index = 0; index < inner.length(); index += 1) {
			char character = inner.charAt(index);

			if (character == '\n' || character == '\r' || Character.isISOControl(character)) {
				return false;
			}

			if (Character.UnicodeBlock.of(character) == Character.UnicodeBlock.BLOCK_ELEMENTS) {
				return false;
			}
		}

		return true;
	}

	private String normalizeBridgePlaceholderValue(String identifier, String value) {
		if (value == null) {
			return "";
		}

		if (!requiresSafePercentEscaping(identifier)) {
			return value;
		}

		return escapePlaceholderPercentsOnce(value);
	}

	private void warnIfSuspiciousRequestedPlaceholder(P player, String action, String identifier) {
		if (Strings.isBlank(identifier)) {
			return;
		}

		String playerName = player == null ? "<unknown>" : players.nameOf(player);

		if (identifier.length() >= LARGE_PLACEHOLDER_IDENTIFIER_LENGTH) {
			logger.warn("TAB bridge request quá dài từ proxy tại backend: action=" + action
				+ " player=" + playerName
				+ " length=" + identifier.length()
				+ " placeholder=" + previewPlaceholder(identifier));
		}

		if (isUnsafeCpuPlaceholder(identifier)) {
			logger.warn("TAB bridge request dùng CPU placeholder không có hậu tố _safe: action=" + action
				+ " player=" + playerName
				+ " placeholder=" + previewPlaceholder(identifier));
		}
	}

	private void warnIfSuspiciousExpansionUpdate(P player, String identifier, String value) {
		if (Strings.isBlank(identifier) && Strings.isBlank(value)) {
			return;
		}

		String playerName = player == null ? "<unknown>" : players.nameOf(player);

		if (identifier != null && identifier.length() >= LARGE_PLACEHOLDER_IDENTIFIER_LENGTH) {
			logger.warn("TAB bridge Expansion gửi identifier quá dài về backend: player=" + playerName
				+ " length=" + identifier.length()
				+ " identifier=" + previewPlaceholder(identifier));
		}

		if (value != null && value.length() >= LARGE_PLACEHOLDER_VALUE_LENGTH) {
			logger.warn("TAB bridge Expansion gửi value quá dài về backend: player=" + playerName
				+ " length=" + value.length()
				+ " identifier=" + previewPlaceholder(identifier)
				+ " value=" + previewPlaceholder(value));
		}
	}

	private boolean requiresSafePercentEscaping(String identifier) {
		if (Strings.isBlank(identifier)) {
			return false;
		}

		return normalizeSnapshotLookupKey(identifier).endsWith("_safe");
	}

	private boolean isUnsafeCpuPlaceholder(String identifier) {
		if (Strings.isBlank(identifier)) {
			return false;
		}

		String normalized = normalizeSnapshotLookupKey(identifier);

		if (Strings.isBlank(normalized) || normalized.endsWith("_safe")) {
			return false;
		}

		return normalized.startsWith("luna_")
			&& (normalized.contains("system_cpu") || normalized.contains("process_cpu"));
	}

	/**
	 * Double every `%`, and leave an already-doubled pair alone.
	 *
	 * Escaping twice is the failure mode worth guarding: a value that has been
	 * through here once and comes back round shows `%%%%` in the tab list, which
	 * reads as corruption rather than as a percent sign.
	 */
	private String escapePlaceholderPercentsOnce(String value) {
		if (value == null || value.indexOf('%') < 0) {
			return value == null ? "" : value;
		}

		StringBuilder escaped = new StringBuilder(value.length() + 8);

		for (int index = 0; index < value.length(); index += 1) {
			char character = value.charAt(index);

			if (character != '%') {
				escaped.append(character);

				continue;
			}

			escaped.append("%%");

			if (index + 1 < value.length() && value.charAt(index + 1) == '%') {
				index += 1;
			}
		}

		return escaped.toString();
	}

	private String previewPlaceholder(String value) {
		if (value == null) {
			return "<null>";
		}

		String sanitized = value.replace("\r", "\\r").replace("\n", "\\n");

		if (sanitized.length() <= PLACEHOLDER_PREVIEW_LENGTH) {
			return sanitized;
		}

		return sanitized.substring(0, PLACEHOLDER_PREVIEW_LENGTH) + "...";
	}

	private boolean hasSnapshotValue(String identifier, Map<String, String> placeholderValues) {
		if (Strings.isBlank(identifier) || placeholderValues == null || placeholderValues.isEmpty()) {
			return false;
		}

		if (placeholderValues.containsKey(identifier)) {
			return true;
		}

		String normalizedKey = normalizeSnapshotLookupKey(identifier);

		return !normalizedKey.isEmpty() && placeholderValues.containsKey(normalizedKey);
	}

	private void sendRelationalPlaceholderUpdates(
		P player,
		String identifier,
		Map<String, String> valuesByTarget,
		Map<String, String> previousValuesByTarget
	) {
		if (player == null || !isRelational(identifier) || valuesByTarget == null || valuesByTarget.isEmpty()) {
			return;
		}

		for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
			String targetName = entry.getKey();
			String value = entry.getValue() == null ? "" : entry.getValue();

			if (value.equals(previousValuesByTarget.get(targetName))) {
				continue;
			}

			sendRelationalPlaceholderUpdate(player, identifier, targetName, value);
		}
	}

	/**
	 * Blank out targets that were sent before and are not in the current set.
	 *
	 * TAB holds a relational value until it is overwritten, so a player who logs
	 * out otherwise keeps their prefix in everyone else's tab list forever. There is
	 * no "forget" in the protocol; an empty string is the only way to say it.
	 */
	private void sendClearedRelationalPlaceholderUpdates(
		P player,
		String identifier,
		Map<String, String> previousValuesByTarget,
		Map<String, String> currentValuesByTarget
	) {
		if (player == null || !isRelational(identifier)
			|| previousValuesByTarget == null || previousValuesByTarget.isEmpty()) {
			return;
		}

		Map<String, String> safeCurrentValues = orEmpty(currentValuesByTarget);

		for (String targetName : previousValuesByTarget.keySet()) {
			if (Strings.isBlank(targetName) || safeCurrentValues.containsKey(targetName)) {
				continue;
			}

			sendRelationalPlaceholderUpdate(player, identifier, targetName, "");
		}
	}

	private void sendRelationalPlaceholderUpdate(P player, String identifier, String targetName, String value) {
		if (player == null || !isRelational(identifier) || Strings.isBlank(targetName)) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				// the same discriminator as a plain update, distinguished only by the
				// extra target field TAB knows to read for a `%rel_` identifier
				output.writeByte(UPDATE_PLACEHOLDER_ID);
				output.writeUTF(identifier);
				output.writeUTF(targetName);
				output.writeUTF(value == null ? "" : value);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB UpdatePlaceholder quan hệ " + identifier
				+ " cho " + players.nameOf(player) + ": " + exception.getMessage());
		}
	}

	private void writeRelationalPlaceholderMap(
		DataOutputStream output,
		Map<String, String> valuesByTarget
	) throws IOException {
		if (valuesByTarget == null || valuesByTarget.isEmpty()) {
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
		if (Strings.isBlank(identifier) || placeholderValues == null || placeholderValues.isEmpty()) {
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

		String value = placeholderValues.get(normalizedKey);

		return value == null ? "" : value;
	}

	/** `%Luna_TPS%` and `luna_tps` are the same key; the map holds the second form. */
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
			Map<String, RequestedPlaceholderState> updated =
				new LinkedHashMap<String, RequestedPlaceholderState>();

			if (existing != null) {
				updated.putAll(existing);
			}

			updated.put(identifier, new RequestedPlaceholderState(normalizeRefreshMillis(refreshMillis)));

			return Collections.unmodifiableMap(updated);
		});
	}

	private void enqueuePayload(UUID playerId, byte[] payload) {
		if (playerId == null || payload == null) {
			return;
		}

		queuedOutgoingPayloadsByPlayer.compute(playerId, (ignored, existing) -> {
			Deque<byte[]> queue = existing == null ? new ArrayDeque<byte[]>() : existing;

			synchronized (queue) {
				queue.addLast(Arrays.copyOf(payload, payload.length));
			}

			return queue;
		});
	}

	/**
	 * Drain the queue in order, stopping at the first payload the bus refuses.
	 *
	 * Stopping rather than skipping is the point: the frames are positional and
	 * TAB applies them in the order they arrive, so dropping one in the middle is
	 * worse than delivering none of the rest.
	 */
	private boolean flushQueuedMessages(P player) {
		if (player == null) {
			return false;
		}

		UUID playerId = players.idOf(player);

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

			if (!bus.send(player, TabBridgeChannels.BRIDGE, nextPayload)) {
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
		if (playerId != null) {
			queuedOutgoingPayloadsByPlayer.remove(playerId);
		}
	}

	private void markPlayerReady(UUID playerId) {
		if (playerId != null) {
			readyPlayers.add(playerId);
		}
	}

	private boolean isPlayerReady(UUID playerId) {
		return playerId != null && readyPlayers.contains(playerId);
	}

	private void startPlaceholderWarmup(UUID playerId) {
		if (playerId != null) {
			placeholderWarmupUntilByPlayer.put(
				playerId,
				Long.valueOf(System.currentTimeMillis() + INITIAL_PLACEHOLDER_WARMUP_MILLIS)
			);
		}
	}

	private boolean isPlaceholderWarmupActive(UUID playerId) {
		if (playerId == null) {
			return false;
		}

		Long warmupUntilMillis = placeholderWarmupUntilByPlayer.get(playerId);

		if (warmupUntilMillis == null) {
			return false;
		}

		if (System.currentTimeMillis() <= warmupUntilMillis.longValue()) {
			return true;
		}

		placeholderWarmupUntilByPlayer.remove(playerId, warmupUntilMillis);

		return false;
	}

	/** TAB's `-1` means "only once"; anything else is floored so a config cannot busy-loop us. */
	private int normalizeRefreshMillis(int refreshMillis) {
		if (refreshMillis == -1) {
			return -1;
		}

		return Math.max(MINIMUM_REFRESH_MILLIS, refreshMillis);
	}

	private boolean placeholderValueChanged(UUID playerId, String identifier, String value) {
		if (playerId == null || Strings.isBlank(identifier)) {
			return false;
		}

		Map<String, String> lastSentValues = lastSentPlaceholderValuesByPlayer.get(playerId);

		if (lastSentValues == null) {
			return true;
		}

		return !Objects.equals(lastSentValues.get(identifier), value);
	}

	private void rememberSentPlaceholderValue(UUID playerId, String identifier, String value) {
		if (playerId == null || Strings.isBlank(identifier)) {
			return;
		}

		lastSentPlaceholderValuesByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, String> updated = existing == null
				? new ConcurrentHashMap<String, String>()
				: existing;

			updated.put(identifier, value == null ? "" : value);

			return updated;
		});
	}

	private void rememberSentRelationalPlaceholderValues(
		UUID playerId,
		String identifier,
		Map<String, String> valuesByTarget
	) {
		if (playerId == null || Strings.isBlank(identifier)) {
			return;
		}

		Map<String, String> safeValues = copyTargetValues(valuesByTarget);

		lastSentRelationalPlaceholderValuesByPlayer.compute(playerId, (ignored, existing) -> {
			Map<String, Map<String, String>> updated = existing == null
				? new ConcurrentHashMap<String, Map<String, String>>()
				: existing;

			updated.put(identifier, safeValues);

			return updated;
		});
	}

	private void markPlaceholderEvaluated(UUID playerId, String identifier) {
		if (playerId == null || Strings.isBlank(identifier)) {
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

	private void sendPermissionResponse(P player, String permission, boolean value) {
		if (player == null || Strings.isBlank(permission)) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(HAS_PERMISSION_ID);
				output.writeUTF(permission);
				output.writeBoolean(value);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logger.debug("Không thể gửi TAB HasPermission " + permission + " cho " + players.nameOf(player)
				+ ": " + exception.getMessage());
		}
	}

	/**
	 * Send only what moved since the last look.
	 *
	 * This runs per player per refresh, so sending the six state packets every time
	 * would be six packets per player per 50ms. The first look sends nothing at all
	 * - there is no previous state to differ from, and the join response has already
	 * carried the initial values.
	 */
	private void syncPlayerState(P player) {
		if (player == null) {
			return;
		}

		PlayerBridgeState current = captureState(player);
		PlayerBridgeState previous = stateByPlayer.put(players.idOf(player), current);

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

	private PlayerBridgeState captureState(P player) {
		TabBridgePlayerState playerState = resolvePlayerState(player);

		return new PlayerBridgeState(
			players.worldName(player),
			resolveGroupName(player),
			players.gameModeId(player),
			players.invisible(player),
			playerState.disguised(),
			playerState.vanished()
		);
	}

	/** Only the true flags: TAB defaults every one of them to false on a join. */
	private void sendInitialStatePackets(P player, PlayerBridgeState state) {
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

	private void sendGameModeUpdate(P player, int gameModeId) {
		sendIntUpdate(player, UPDATE_GAME_MODE_ID, gameModeId, "UpdateGameMode");
	}

	private void sendInvisibleUpdate(P player, boolean invisible) {
		sendBooleanUpdate(player, INVISIBLE_ID, invisible, "Invisible");
	}

	private void sendDisguisedUpdate(P player, boolean disguised) {
		sendBooleanUpdate(player, DISGUISED_ID, disguised, "Disguised");
	}

	private void sendVanishedUpdate(P player, boolean vanished) {
		sendBooleanUpdate(player, VANISHED_ID, vanished, "Vanished");
	}

	private void sendWorldUpdate(P player, String worldName) {
		sendTextUpdate(player, SET_WORLD_ID, worldName == null ? "unknown" : worldName, "SetWorld");
	}

	private void sendGroupUpdate(P player, String groupName) {
		sendTextUpdate(player, SET_GROUP_ID, groupName == null ? "" : groupName, "SetGroup");
	}

	private void sendBooleanUpdate(P player, byte discriminator, boolean value, String label) {
		if (player == null) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(discriminator);
				output.writeBoolean(value);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logSendFailure(player, label, exception);
		}
	}

	private void sendIntUpdate(P player, byte discriminator, int value, String label) {
		if (player == null) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(discriminator);
				output.writeInt(value);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logSendFailure(player, label, exception);
		}
	}

	private void sendTextUpdate(P player, byte discriminator, String value, String label) {
		if (player == null) {
			return;
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);

			try {
				output.writeByte(discriminator);
				output.writeUTF(value);
				sendRaw(player, bytes.toByteArray());
			} finally {
				output.close();
			}
		} catch (IOException exception) {
			logSendFailure(player, label, exception);
		}
	}

	private void logSendFailure(P player, String label, IOException exception) {
		logger.debug("Không thể gửi TAB " + label + " cho " + players.nameOf(player)
			+ ": " + exception.getMessage());
	}

	private boolean resolvePermission(P player, String permission) {
		if (player == null || Strings.isBlank(permission) || permissions == null) {
			return false;
		}

		return permissions.hasPermission(players.idOf(player), permission);
	}

	private String resolveGroupName(P player) {
		if (player == null || permissions == null) {
			return "";
		}

		String groupName = permissions.groupName(players.idOf(player));

		return groupName == null ? "" : groupName;
	}

	private TabBridgePlayerState resolvePlayerState(P player) {
		if (player == null) {
			return TabBridgePlayerState.DEFAULT;
		}

		TabBridgePlayerState state = playerStateSource.resolve(player);

		return state == null ? TabBridgePlayerState.DEFAULT : state;
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

	private static boolean isRelational(String identifier) {
		return identifier != null && identifier.startsWith(RELATIONAL_PREFIX);
	}

	private static Map<String, String> orEmpty(Map<String, String> values) {
		return values == null ? Collections.<String, String>emptyMap() : values;
	}

	/** A defensive copy with blank target names dropped; TAB cannot address one. */
	private static Map<String, String> copyTargetValues(Map<String, String> valuesByTarget) {
		Map<String, String> copied = new LinkedHashMap<String, String>();

		if (valuesByTarget != null) {
			for (Map.Entry<String, String> entry : valuesByTarget.entrySet()) {
				String targetName = entry.getKey();

				if (Strings.isBlank(targetName)) {
					continue;
				}

				copied.put(targetName, entry.getValue() == null ? "" : entry.getValue());
			}
		}

		return Collections.unmodifiableMap(copied);
	}

	/**
	 * How often TAB wants one identifier re-evaluated, and when it is next due.
	 *
	 * The first evaluation is always allowed through whatever the interval says, so
	 * an identifier registered with a long refresh still gets a value immediately
	 * rather than showing blank until its first period elapses.
	 */
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

	/** The six facts the bridge watches for a change, captured together. */
	private static final class PlayerBridgeState {
		private final String worldName;
		private final String groupName;
		private final int gameModeId;
		private final boolean invisible;
		private final boolean disguised;
		private final boolean vanished;

		private PlayerBridgeState(
			String worldName,
			String groupName,
			int gameModeId,
			boolean invisible,
			boolean disguised,
			boolean vanished
		) {
			this.worldName = worldName == null ? "unknown" : worldName;
			this.groupName = groupName == null ? "" : groupName;
			this.gameModeId = gameModeId;
			this.invisible = invisible;
			this.disguised = disguised;
			this.vanished = vanished;
		}

		private String worldName() {
			return worldName;
		}

		private String groupName() {
			return groupName;
		}

		private int gameModeId() {
			return gameModeId;
		}

		private boolean invisible() {
			return invisible;
		}

		private boolean disguised() {
			return disguised;
		}

		private boolean vanished() {
			return vanished;
		}
	}
}
