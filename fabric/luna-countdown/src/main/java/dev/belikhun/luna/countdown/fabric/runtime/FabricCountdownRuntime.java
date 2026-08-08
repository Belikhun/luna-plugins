package dev.belikhun.luna.countdown.fabric.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.countdown.fabric.text.Broadcasts;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The countdowns an operator has running, and the announcements they make.
 *
 * Ticking happens off the server thread on a one-second scheduler, because the
 * remaining time is derived from the wall clock rather than accumulated per
 * tick: a server that stalls still finishes its countdown when it said it would.
 * Everything that reaches a player goes back through {@link Broadcasts}, which
 * returns to the server thread.
 */
public final class FabricCountdownRuntime implements AutoCloseable {
	private static final String DEFAULT_TITLE = "Sự kiện";

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final ScheduledExecutorService executor;
	private final AtomicInteger nextId;
	private final Map<Integer, RunningCountdown> activeCountdowns;

	public FabricCountdownRuntime(LunaLogger logger, MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
		this.logger = logger.scope("Runtime");
		this.nextId = new AtomicInteger(1);
		this.activeCountdowns = new ConcurrentHashMap<>();
		this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-countdown-fabric");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Announce a countdown and start ticking it.
	 *
	 * @return the id an operator stops it by
	 */
	public int start(String title, int seconds) {
		int safeSeconds = Math.max(1, seconds);
		int id = nextId.getAndIncrement();
		String safeTitle = title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();

		RunningCountdown countdown = new RunningCountdown(id, safeTitle, safeSeconds);

		activeCountdowns.put(id, countdown);
		countdown.task = executor.scheduleAtFixedRate(countdown::tick, 0L, 1L, TimeUnit.SECONDS);

		Broadcasts.chat(server, "Sự kiện " + countdown.label() + " sẽ bắt đầu sau " + readableTime(safeSeconds) + " nữa!");
		logger.audit("Đã tạo countdown #" + id + " title=" + safeTitle + " seconds=" + safeSeconds);

		return id;
	}

	/** Stop one countdown, announcing why. False when no such countdown runs. */
	public boolean stop(int id, String reason) {
		RunningCountdown countdown = activeCountdowns.remove(id);

		if (countdown == null) {
			return false;
		}

		countdown.cancel();

		String safeReason = reason == null ? "Đã hủy." : reason;

		Broadcasts.chat(server, "Sự kiện " + countdown.label() + " đã bị hủy! " + safeReason);
		logger.warn("Countdown #" + id + " đã bị hủy: " + safeReason);

		return true;
	}

	/** Stop every running countdown, announcing each one. */
	public void stopAll(String reason) {
		for (Integer id : new ArrayList<>(activeCountdowns.keySet())) {
			stop(id, reason);
		}
	}

	/** Every countdown currently running, newest id last. */
	public List<CountdownSnapshot> activeCountdowns() {
		List<CountdownSnapshot> snapshots = new ArrayList<>();

		for (RunningCountdown countdown : activeCountdowns.values()) {
			snapshots.add(countdown.snapshot());
		}

		snapshots.sort((left, right) -> Integer.compare(left.id(), right.id()));

		return List.copyOf(snapshots);
	}

	@Override
	public void close() {
		stopAll("Countdown runtime đang tắt.");
		executor.shutdownNow();
	}

	private static String readableTime(double seconds) {
		return Formatters.compactDuration(Duration.ofSeconds((long) Math.ceil(Math.max(1D, seconds))));
	}

	private final class RunningCountdown {
		private final int id;
		private final String title;
		private final int totalSeconds;
		private final long startedAtMillis;
		private volatile ScheduledFuture<?> task;

		private RunningCountdown(int id, String title, int totalSeconds) {
			this.id = id;
			this.title = title;
			this.totalSeconds = totalSeconds;
			this.startedAtMillis = System.currentTimeMillis();
		}

		private void tick() {
			double remaining = remainingSeconds();

			if (remaining > 0D) {
				Broadcasts.actionBar(server, "#" + id + " " + title + " sau " + readableTime(remaining));

				if (remaining <= 10D || Math.floor(remaining) % 60D == 0D) {
					logger.debug("Countdown #" + id + " còn " + String.format(Locale.ROOT, "%.1f", remaining) + "s.");
				}

				return;
			}

			// another thread may be stopping this same countdown; whoever removes
			// it owns the announcement, so the player never sees both endings
			if (activeCountdowns.remove(id, this)) {
				cancel();
				Broadcasts.chat(server, "Sự kiện " + label() + " đã bắt đầu!");
				logger.success("Countdown #" + id + " đã hoàn tất: " + title);
			}
		}

		private double remainingSeconds() {
			long elapsedMillis = System.currentTimeMillis() - startedAtMillis;

			return Math.max(0D, (totalSeconds * 1000D - elapsedMillis) / 1000D);
		}

		private String label() {
			return "(#" + id + ") " + title;
		}

		private CountdownSnapshot snapshot() {
			return new CountdownSnapshot(id, title, totalSeconds, remainingSeconds());
		}

		private void cancel() {
			ScheduledFuture<?> currentTask = task;

			if (currentTask != null) {
				currentTask.cancel(false);
				task = null;
			}
		}
	}
}
