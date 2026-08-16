package dev.belikhun.luna.legacy.tabbridge;

import dev.belikhun.luna.legacy.placeholder.PlaceholderService;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The clock that pushes fresh values at TAB.
 *
 * TAB asks for a refresh interval per identifier but never polls; the backend is
 * expected to volunteer values, so something has to tick. A timer thread does the
 * waiting and hands the actual work to the server thread, because resolving a
 * placeholder reads the world and the player list.
 */
public final class TabBridgePlaceholderUpdater<P> {
	/**
	 * How often this backend volunteers values, and so the fastest the tab list can
	 * change.
	 *
	 * Deliberately 100ms rather than one tick. TAB asks for a refresh interval per
	 * identifier and the runtime honours it, but two things quietly floor to 50ms:
	 * this clock, and the fallback used when a registration arrives carrying no
	 * interval. A value that genuinely moves every sample - tick duration, cpu,
	 * memory - therefore reached the client twenty times a second, which a 1.12
	 * client redraws as a visible strobe. 100ms is TAB's own configured default
	 * here, so this asks for nothing TAB was not already going to use.
	 *
	 * The modern trunks stay at 50ms on purpose: their clients redraw it without
	 * complaint, and this cap belongs to the era that cannot.
	 */
	private static final long REFRESH_INTERVAL_MILLIS = 100L;

	private final TabPlayerBridge<P> players;
	private final TabBridgeRuntime<P> runtime;
	private final TabBridgeRelationalPlaceholderSource<P> relationalPlaceholderSource;
	private final PlaceholderService<P> placeholderService;
	private final ScheduledExecutorService refreshExecutor;

	/**
	 * Whether a refresh is already waiting for the server thread.
	 *
	 * The timer fires every 50ms whatever the server is doing, so on a backend
	 * ticking slower than that - which 1.12.2 under load will - every fire would
	 * queue another refresh behind the last one and the queue would never drain.
	 * Skipping while one is outstanding costs nothing: the skipped refresh would
	 * have read the same values as the one already queued.
	 */
	private final AtomicBoolean refreshInFlight;

	private volatile boolean closed;

	public TabBridgePlaceholderUpdater(
		TabPlayerBridge<P> players,
		TabBridgeRuntime<P> runtime,
		TabBridgeRelationalPlaceholderSource<P> relationalPlaceholderSource,
		PlaceholderService<P> placeholderService
	) {
		this.players = Objects.requireNonNull(players, "players");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.relationalPlaceholderSource = Objects.requireNonNull(relationalPlaceholderSource, "relationalPlaceholderSource");
		this.placeholderService = Objects.requireNonNull(placeholderService, "placeholderService");
		this.refreshInFlight = new AtomicBoolean(false);
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable task) {
				Thread thread = new Thread(task, "luna-tabbridge-placeholders");

				thread.setDaemon(true);

				return thread;
			}
		});

		this.refreshExecutor.scheduleAtFixedRate(
			new Runnable() {
				@Override
				public void run() {
					scheduleRefresh();
				}
			},
			REFRESH_INTERVAL_MILLIS,
			REFRESH_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	private void scheduleRefresh() {
		if (closed || !refreshInFlight.compareAndSet(false, true)) {
			return;
		}

		players.onServerThread(new Runnable() {
			@Override
			public void run() {
				try {
					if (!closed) {
						refreshOnlinePlayers();
					}
				} finally {
					refreshInFlight.set(false);
				}
			}
		});
	}

	/**
	 * Re-sample the shared statistics once, then push every online player's values.
	 *
	 * The re-sample belongs here rather than on the tick listener's one-second
	 * clock: cpu and memory are the figures this drives, and at one second they
	 * read as frozen. This runs on {@link #REFRESH_INTERVAL_MILLIS}, so they move
	 * at that rate and no faster - which is the whole reason that constant is not
	 * one tick.
	 */
	public void refreshOnlinePlayers() {
		if (closed) {
			return;
		}

		placeholderService.refreshSharedSnapshot();

		for (P player : players.online()) {
			refreshPlayer(player);
		}
	}

	/**
	 * Push one player's values.
	 *
	 * Only the identifiers TAB actually registered are resolved. That is what keeps
	 * this affordable at 20 refreshes a second: the placeholder service is asked for
	 * a handful of values, not for everything it could publish.
	 */
	public void refreshPlayer(P player) {
		if (closed || player == null) {
			return;
		}

		runtime.updatePlayerPlaceholders(player, placeholderService.snapshot(
			player,
			runtime.requestedPlaceholderIdentifiers(players.idOf(player))
		));

		runtime.updatePlayerRelationalPlaceholders(player, relationalPlaceholderSource.resolve(player));
	}

	/** Where the runtime sends an identifier the snapshot did not carry. */
	public String resolvePlaceholder(P player, String identifier) {
		if (closed) {
			return null;
		}

		return placeholderService.resolvePlaceholder(player, identifier);
	}

	public void close() {
		closed = true;
		refreshExecutor.shutdownNow();
	}
}
