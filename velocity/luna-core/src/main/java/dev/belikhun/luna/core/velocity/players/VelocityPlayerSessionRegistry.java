package dev.belikhun.luna.core.velocity.players;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tracks how long each player has been connected and keeps a rolling log of
 * join/leave/switch activity.
 *
 * Velocity itself only reports who is connected right now, so session length and
 * recent activity have to be observed as they happen. The registry is created once
 * for the plugin's lifetime — not per config reload — because a reload must not
 * reset everyone's session clock or forget the history.
 */
public final class VelocityPlayerSessionRegistry {
	/** Activity entries kept in memory; the console shows a page of these. */
	private static final int MAX_HISTORY = 300;

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final ConcurrentMap<UUID, Session> sessions;
	private final Deque<Activity> history;
	private final List<Consumer<Activity>> listeners;

	public VelocityPlayerSessionRegistry(ProxyServer proxyServer, LunaLogger logger) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("PlayerSessions");
		this.sessions = new ConcurrentHashMap<>();
		this.history = new ArrayDeque<>();
		this.listeners = new CopyOnWriteArrayList<>();
	}

	/** One connected player's session state. */
	public record Session(UUID uuid, String username, long connectedAtEpochMillis, String server) {
	}

	/** Something that happened to a player: a join, a leave, or a server switch. */
	public record Activity(
		String type,
		UUID uuid,
		String username,
		String server,
		String previousServer,
		long atEpochMillis,
		long sessionMillis
	) {
	}

	/** Register for activity as it happens — used by the SSE endpoint. */
	public void addListener(Consumer<Activity> listener) {
		if (listener != null) {
			listeners.add(listener);
		}
	}

	/** Stop receiving activity. */
	public void removeListener(Consumer<Activity> listener) {
		listeners.remove(listener);
	}

	/** Session state for a connected player, if the proxy has seen them log in. */
	public Optional<Session> session(UUID uuid) {
		return Optional.ofNullable(sessions.get(uuid));
	}

	/** How long a player has been connected, or 0 when unknown. */
	public long sessionMillis(UUID uuid) {
		Session session = sessions.get(uuid);
		if (session == null) {
			return 0L;
		}

		return Math.max(0L, System.currentTimeMillis() - session.connectedAtEpochMillis());
	}

	/** Recent activity, newest first, capped at {@code limit} entries. */
	public List<Activity> recentActivity(int limit) {
		int capped = limit <= 0 ? MAX_HISTORY : Math.min(limit, MAX_HISTORY);
		List<Activity> ordered = new ArrayList<>(capped);

		synchronized (history) {
			for (Activity activity : history) {
				if (ordered.size() >= capped) {
					break;
				}
				ordered.add(activity);
			}
		}

		return List.copyOf(ordered);
	}

	/** Number of players the proxy currently reports as connected. */
	public int onlineCount() {
		return proxyServer.getPlayerCount();
	}

	@Subscribe
	public void onPostLogin(PostLoginEvent event) {
		Player player = event.getPlayer();
		long now = System.currentTimeMillis();

		sessions.put(player.getUniqueId(), new Session(player.getUniqueId(), player.getUsername(), now, ""));
		record(new Activity("join", player.getUniqueId(), player.getUsername(), "", "", now, 0L));
	}

	@Subscribe
	public void onServerConnected(ServerConnectedEvent event) {
		Player player = event.getPlayer();
		String server = normalize(event.getServer().getServerInfo().getName());
		String previous = event.getPreviousServer()
			.map(previousServer -> normalize(previousServer.getServerInfo().getName()))
			.orElse("");

		long now = System.currentTimeMillis();

		Session updated = sessions.compute(player.getUniqueId(), (uuid, existing) -> existing == null
			? new Session(uuid, player.getUsername(), now, server)
			: new Session(uuid, existing.username(), existing.connectedAtEpochMillis(), server));

		// A first connect is already reported as "join"; only real switches are moves.
		if (!previous.isBlank()) {
			record(new Activity(
				"switch",
				player.getUniqueId(),
				player.getUsername(),
				server,
				previous,
				now,
				Math.max(0L, now - updated.connectedAtEpochMillis())
			));
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		Player player = event.getPlayer();
		Session session = sessions.remove(player.getUniqueId());
		long now = System.currentTimeMillis();

		record(new Activity(
			"leave",
			player.getUniqueId(),
			player.getUsername(),
			session == null ? "" : session.server(),
			"",
			now,
			session == null ? 0L : Math.max(0L, now - session.connectedAtEpochMillis())
		));
	}

	private void record(Activity activity) {
		synchronized (history) {
			history.addFirst(activity);

			while (history.size() > MAX_HISTORY) {
				history.removeLast();
			}
		}

		for (Consumer<Activity> listener : listeners) {
			try {
				listener.accept(activity);
			} catch (RuntimeException exception) {
				logger.error("Listener hoạt động người chơi lỗi: " + exception.getMessage(), exception);
			}
		}
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
