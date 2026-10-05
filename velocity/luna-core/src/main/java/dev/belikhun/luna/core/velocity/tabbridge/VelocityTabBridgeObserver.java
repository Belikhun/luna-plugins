package dev.belikhun.luna.core.velocity.tabbridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Watches TAB's bridge channel as frames pass through the proxy and keeps
 * counters, so a backend's "I sent it" can be checked against "the proxy saw it".
 *
 * Only backend-to-proxy frames are visible: Velocity fires the plugin-message
 * event for those, while frames TAB itself sends to a backend never go through
 * the event bus. The observer never touches the event result, so TAB keeps
 * receiving exactly what it did before.
 */
public final class VelocityTabBridgeObserver {
	/** TAB's bridge channel; the suffix is TAB's own protocol version. */
	public static final String CHANNEL = "tab:bridge-6";
	private static final int LAST_UPDATES_KEPT = 64;
	private static final int IDENTIFIERS_KEPT = 512;
	private static final int UPDATE_PLACEHOLDER = 8;
	private static final String RELATIONAL_PREFIX = "%rel_";
	private static final String[] PACKET_NAMES = {
		"?", "UpdateGameMode", "HasPermission", "Invisible", "Disguised",
		"SetWorld", "SetGroup", "Vanished", "UpdatePlaceholder", "PlayerJoinResponse"
	};

	/** One placeholder update the proxy saw. */
	public record PlaceholderUpdate(String identifier, String target, String value, String backend, long atEpochMillis) {
	}

	/** Frame counters for one backend. */
	public static final class BackendStats {
		private final LongAdder frames = new LongAdder();
		private final LongAdder bytes = new LongAdder();
		private final LongAdder unparsed = new LongAdder();
		private final LongAdder[] byPacket = new LongAdder[PACKET_NAMES.length];
		private final Map<String, LongAdder> byIdentifier = new ConcurrentHashMap<>();
		private final AtomicLong lastFrameAt = new AtomicLong();

		private BackendStats() {
			for (int index = 0; index < byPacket.length; index++) {
				byPacket[index] = new LongAdder();
			}
		}

		public long frames() {
			return frames.sum();
		}

		public long bytes() {
			return bytes.sum();
		}

		public long unparsed() {
			return unparsed.sum();
		}

		public long lastFrameAtEpochMillis() {
			return lastFrameAt.get();
		}

		public long packetCount(int id) {
			if (id < 0 || id >= byPacket.length) {
				return 0L;
			}

			return byPacket[id].sum();
		}

		public Map<String, LongAdder> identifierCounts() {
			return byIdentifier;
		}
	}

	/** Frame counters and the recent updates for one player. */
	public static final class PlayerStats {
		private final LongAdder frames = new LongAdder();
		private final LongAdder placeholderUpdates = new LongAdder();
		private final Deque<PlaceholderUpdate> recent = new ArrayDeque<>();
		private volatile String lastBackend;

		public long frames() {
			return frames.sum();
		}

		public long placeholderUpdates() {
			return placeholderUpdates.sum();
		}

		public String lastBackend() {
			return lastBackend;
		}

		public synchronized List<PlaceholderUpdate> recent(int limit) {
			List<PlaceholderUpdate> copy = new ArrayList<>(recent);
			int from = Math.max(0, copy.size() - Math.max(0, limit));
			return List.copyOf(copy.subList(from, copy.size()));
		}

		private synchronized void remember(PlaceholderUpdate update) {
			recent.addLast(update);

			while (recent.size() > LAST_UPDATES_KEPT) {
				recent.removeFirst();
			}
		}
	}

	private final LunaLogger logger;
	private final Map<String, BackendStats> backends = new ConcurrentHashMap<>();
	private final Map<UUID, PlayerStats> players = new ConcurrentHashMap<>();
	private volatile long sinceEpochMillis = System.currentTimeMillis();

	public VelocityTabBridgeObserver(LunaLogger logger) {
		this.logger = logger.scope("TabBridgeObserver");
	}

	@Subscribe
	public void onPluginMessage(PluginMessageEvent event) {
		if (!(event.getIdentifier() instanceof MinecraftChannelIdentifier identifier)) {
			return;
		}

		if (!CHANNEL.equals(identifier.getId())) {
			return;
		}

		if (!(event.getSource() instanceof ServerConnection connection)) {
			return;
		}

		String backend = connection.getServerInfo().getName();
		UUID playerId = connection.getPlayer().getUniqueId();
		byte[] data = event.getData();
		long now = System.currentTimeMillis();

		BackendStats backendStats = backends.computeIfAbsent(backend, ignored -> new BackendStats());
		PlayerStats playerStats = players.computeIfAbsent(playerId, ignored -> new PlayerStats());
		backendStats.frames.increment();
		backendStats.bytes.add(data.length);
		backendStats.lastFrameAt.set(now);
		playerStats.frames.increment();
		playerStats.lastBackend = backend;

		try {
			parse(data, backend, now, backendStats, playerStats);
		} catch (IOException | RuntimeException exception) {
			backendStats.unparsed.increment();
			logger.debug("Không đọc được frame tab bridge từ " + backend + ": " + exception);
		}
	}

	private void parse(byte[] data, String backend, long now, BackendStats backendStats, PlayerStats playerStats) throws IOException {
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
		int id = input.readUnsignedByte();

		if (id >= PACKET_NAMES.length) {
			backendStats.unparsed.increment();
			return;
		}

		backendStats.byPacket[id].increment();

		if (id != UPDATE_PLACEHOLDER) {
			return;
		}

		String placeholder = input.readUTF();
		String target = null;

		if (placeholder.startsWith(RELATIONAL_PREFIX)) {
			target = input.readUTF();
		}

		String value = input.readUTF();
		playerStats.placeholderUpdates.increment();
		playerStats.remember(new PlaceholderUpdate(placeholder, target, value, backend, now));

		if (backendStats.byIdentifier.size() < IDENTIFIERS_KEPT || backendStats.byIdentifier.containsKey(placeholder)) {
			backendStats.byIdentifier.computeIfAbsent(placeholder, ignored -> new LongAdder()).increment();
		}
	}

	/** Drops every counter and starts a new observation window. */
	public void reset() {
		backends.clear();
		players.clear();
		sinceEpochMillis = System.currentTimeMillis();
	}

	public long sinceEpochMillis() {
		return sinceEpochMillis;
	}

	public Map<String, BackendStats> backends() {
		return backends;
	}

	public Optional<PlayerStats> player(UUID playerId) {
		return Optional.ofNullable(players.get(playerId));
	}

	/**
	 * Formats the per-backend counters as lines.
	 *
	 * @param backendFilter a backend name to restrict to, or null for all
	 * @return the lines, header included
	 */
	public List<String> statsLines(String backendFilter) {
		List<String> lines = new ArrayList<>();
		long windowSeconds = Math.max(1L, (System.currentTimeMillis() - sinceEpochMillis) / 1000L);
		lines.add("── tab bridge frames seen by the proxy · window " + windowSeconds + "s · channel " + CHANNEL + " ──");

		List<Map.Entry<String, BackendStats>> sorted = new ArrayList<>(backends.entrySet());
		sorted.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));

		if (sorted.isEmpty()) {
			lines.add("  (no frames yet; TAB must be installed for the channel to be registered)");
			return lines;
		}

		for (Map.Entry<String, BackendStats> entry : sorted) {
			String backend = entry.getKey();

			if (backendFilter != null && !backendFilter.equalsIgnoreCase(backend)) {
				continue;
			}

			BackendStats stats = entry.getValue();
			long ago = stats.lastFrameAtEpochMillis() == 0L ? -1L : (System.currentTimeMillis() - stats.lastFrameAtEpochMillis()) / 1000L;
			lines.add(String.format(
				Locale.ROOT,
				"  %s · %d frames · %d KB · %.1f frames/s · last %ss ago · unparsed %d",
				backend,
				stats.frames(),
				stats.bytes() / 1024L,
				stats.frames() / (double) windowSeconds,
				ago < 0 ? "-" : String.valueOf(ago),
				stats.unparsed()
			));

			StringBuilder packets = new StringBuilder("    packets:");
			for (int id = 1; id < PACKET_NAMES.length; id++) {
				long count = stats.packetCount(id);

				if (count > 0) {
					packets.append(' ').append(PACKET_NAMES[id]).append('=').append(count);
				}
			}
			lines.add(packets.toString());

			List<Map.Entry<String, LongAdder>> identifiers = new ArrayList<>(stats.identifierCounts().entrySet());
			identifiers.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> item) -> item.getValue().sum()).reversed());
			int shown = 0;

			for (Map.Entry<String, LongAdder> identifier : identifiers) {
				if (shown++ >= 15) {
					lines.add("    … " + (identifiers.size() - 15) + " more identifiers");
					break;
				}

				lines.add("    " + identifier.getKey() + " ×" + identifier.getValue().sum());
			}
		}

		return lines;
	}

	/**
	 * Formats a player's recent placeholder updates as lines.
	 *
	 * @param playerId   the player
	 * @param playerName the name to print
	 * @param limit      how many updates to show, newest last
	 * @return the lines, header included
	 */
	public List<String> recentLines(UUID playerId, String playerName, int limit) {
		List<String> lines = new ArrayList<>();
		PlayerStats stats = players.get(playerId);

		if (stats == null) {
			lines.add("  (no bridge frames seen for " + playerName + ")");
			return lines;
		}

		lines.add("── last placeholder updates for " + playerName + " · " + stats.placeholderUpdates()
			+ " updates in " + stats.frames() + " frames · via " + stats.lastBackend() + " ──");
		long now = System.currentTimeMillis();

		for (PlaceholderUpdate update : stats.recent(limit)) {
			String target = update.target() == null ? "" : " → " + update.target();
			lines.add(String.format(
				Locale.ROOT,
				"  -%5dms  %s%s = %s",
				now - update.atEpochMillis(),
				update.identifier(),
				target,
				update.value()
			));
		}

		return lines;
	}

	/**
	 * The counters as a JSON payload.
	 *
	 * @return the payload map
	 */
	public Map<String, Object> toJson() {
		LunaJson.Arr backendArray = LunaJson.arr();

		for (Map.Entry<String, BackendStats> entry : backends.entrySet()) {
			BackendStats stats = entry.getValue();
			LunaJson.Obj packets = LunaJson.obj();

			for (int id = 1; id < PACKET_NAMES.length; id++) {
				packets.put(PACKET_NAMES[id], stats.packetCount(id));
			}

			LunaJson.Obj identifiers = LunaJson.obj();
			for (Map.Entry<String, LongAdder> identifier : stats.identifierCounts().entrySet()) {
				identifiers.put(identifier.getKey(), identifier.getValue().sum());
			}

			backendArray.add(LunaJson.obj()
				.put("backend", entry.getKey())
				.put("frames", stats.frames())
				.put("bytes", stats.bytes())
				.put("unparsed", stats.unparsed())
				.put("lastFrameAtEpochMillis", stats.lastFrameAtEpochMillis())
				.put("packets", packets.map())
				.put("identifiers", identifiers.map())
				.map());
		}

		return LunaJson.obj()
			.put("channel", CHANNEL)
			.put("sinceEpochMillis", sinceEpochMillis)
			.put("backends", backendArray.list())
			.map();
	}

	/**
	 * A player's recent updates as a JSON payload.
	 *
	 * @param playerId the player
	 * @param limit    how many updates to include
	 * @return the payload map, empty counters when the player was never seen
	 */
	public Map<String, Object> playerJson(UUID playerId, int limit) {
		PlayerStats stats = players.get(playerId);
		LunaJson.Arr updates = LunaJson.arr();

		if (stats != null) {
			for (PlaceholderUpdate update : stats.recent(limit)) {
				updates.add(LunaJson.obj()
					.put("identifier", update.identifier())
					.put("target", update.target())
					.put("value", update.value())
					.put("backend", update.backend())
					.put("atEpochMillis", update.atEpochMillis())
					.map());
			}
		}

		return LunaJson.obj()
			.put("uuid", playerId.toString())
			.put("frames", stats == null ? 0L : stats.frames())
			.put("placeholderUpdates", stats == null ? 0L : stats.placeholderUpdates())
			.put("lastBackend", stats == null ? null : stats.lastBackend())
			.put("recent", updates.list())
			.map();
	}
}
