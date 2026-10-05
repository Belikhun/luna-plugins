package dev.belikhun.luna.tabbridge.mc.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The clock behind the bridge: every interval, on the server thread, each
 * online player is refreshed once.
 *
 * A refresh that has not finished by the next tick is not queued behind
 * itself; on a server ticking slower than the interval that queue would only
 * grow, which is what the 1.12 line found out.
 */
public final class TabBridgePlaceholderUpdater implements AutoCloseable {
	/**
	 * Ten times a second. TAB's own floor is 50 ms, but at that rate a client
	 * redraws tick and CPU values as a visible strobe.
	 */
	private static final long REFRESH_INTERVAL_MILLIS = 100L;

	private final MinecraftServer server;
	private final TabBridgeRuntime runtime;
	private final ScheduledExecutorService refreshExecutor;
	private final AtomicBoolean inFlight = new AtomicBoolean(false);
	private volatile boolean closed;

	public TabBridgePlaceholderUpdater(MinecraftServer server, TabBridgeRuntime runtime) {
		this.server = Objects.requireNonNull(server, "server");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-tabbridge-placeholders");
			thread.setDaemon(true);
			return thread;
		});
		this.refreshExecutor.scheduleAtFixedRate(this::tick, REFRESH_INTERVAL_MILLIS, REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** Refreshes every online player now, on the server thread. */
	public void refreshOnlinePlayers() {
		if (closed) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			refreshPlayer(player);
		}
	}

	/** Refreshes one player now; the caller is on the server thread. */
	public void refreshPlayer(ServerPlayer player) {
		if (closed || player == null) {
			return;
		}

		runtime.refresh(player);
	}

	@Override
	public void close() {
		closed = true;
		refreshExecutor.shutdownNow();
	}

	private void tick() {
		if (closed || !inFlight.compareAndSet(false, true)) {
			return;
		}

		server.execute(() -> {
			try {
				if (!closed) {
					refreshOnlinePlayers();
				}
			} finally {
				inFlight.set(false);
			}
		});
	}
}
