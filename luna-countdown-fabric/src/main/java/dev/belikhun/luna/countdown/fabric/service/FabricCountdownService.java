package dev.belikhun.luna.countdown.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FabricCountdownService implements AutoCloseable {
	private static final long UPDATE_PERIOD_MILLIS = 100L;
	private static final double AUTO_CLOSE_AFTER_SECONDS = 5.0d;

	private final LunaLogger logger;
	private final ScheduledExecutorService scheduler;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final Map<Integer, ActiveCountdown> activeById = new ConcurrentHashMap<>();
	private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

	public FabricCountdownService(LunaLogger logger) {
		this.logger = logger.scope("Countdown");
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-countdown-fabric");
			thread.setDaemon(true);
			return thread;
		});
	}

	public ActiveCountdown start(String title, int seconds, CountdownListener listener) {
		if (seconds <= 0) {
			throw new IllegalArgumentException("seconds must be > 0");
		}

		CountdownListener safeListener = listener == null ? CountdownListener.noop() : listener;
		int id = nextId.getAndIncrement();
		long targetEpochMillis = System.currentTimeMillis() + (seconds * 1000L);
		ActiveCountdown countdown = new ActiveCountdown(id, title == null ? "" : title, seconds, targetEpochMillis, safeListener);
		activeById.put(id, countdown);
		safeListener.onBegin(countdown.snapshot(false, false));
		countdown.future = scheduler.scheduleAtFixedRate(() -> tick(countdown), 0L, UPDATE_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
		logger.audit("Đã bắt đầu countdown #" + id + " trong " + seconds + " giây");
		return countdown;
	}

	public boolean stop(int id, String reason) {
		ActiveCountdown countdown = activeById.remove(id);
		if (countdown == null) {
			return false;
		}

		countdown.cancelled = true;
		cancelFuture(countdown.future);
		countdown.listener.onStop(countdown.snapshot(false, true), reason == null ? "" : reason);
		logger.audit("Đã dừng countdown #" + id);
		return true;
	}

	public void stopAll(String reason) {
		for (ActiveCountdown countdown : new ArrayList<>(activeById.values())) {
			stop(countdown.id, reason);
		}
	}

	public Collection<CountdownSnapshot> snapshots() {
		List<CountdownSnapshot> snapshots = new ArrayList<>();
		for (ActiveCountdown countdown : activeById.values()) {
			snapshots.add(countdown.snapshot(countdown.ended, countdown.cancelled));
		}
		return snapshots;
	}

	public void handlePlayerJoin(UUID playerId) {
		if (playerId == null) {
			return;
		}
		onlinePlayers.add(playerId);
	}

	public void handlePlayerQuit(UUID playerId) {
		if (playerId == null) {
			return;
		}
		onlinePlayers.remove(playerId);
	}

	public int onlinePlayerCount() {
		return onlinePlayers.size();
	}

	@Override
	public void close() {
		stopAll("runtime shutdown");
		onlinePlayers.clear();
		scheduler.shutdownNow();
	}

	private void tick(ActiveCountdown countdown) {
		long now = System.currentTimeMillis();
		double remainSeconds = (double) (countdown.targetEpochMillis - now) / 1000d;
		double progress = remainSeconds / countdown.totalSeconds;

		if (progress >= 0d) {
			countdown.listener.onUpdate(countdown.snapshot(false, false), remainSeconds, progress);
			return;
		}

		if (!countdown.ended) {
			countdown.ended = true;
			countdown.listener.onComplete(countdown.snapshot(true, false));
		}

		if (remainSeconds < -AUTO_CLOSE_AFTER_SECONDS) {
			activeById.remove(countdown.id);
			cancelFuture(countdown.future);
		}
	}

	private void cancelFuture(ScheduledFuture<?> future) {
		if (future != null) {
			future.cancel(false);
		}
	}

	public record CountdownSnapshot(
		int id,
		String title,
		int totalSeconds,
		long targetEpochMillis,
		boolean ended,
		boolean cancelled
	) {
	}

	public interface CountdownListener {
		void onBegin(CountdownSnapshot snapshot);

		void onUpdate(CountdownSnapshot snapshot, double remainSeconds, double progress);

		void onComplete(CountdownSnapshot snapshot);

		void onStop(CountdownSnapshot snapshot, String reason);

		static CountdownListener noop() {
			return new CountdownListener() {
				@Override
				public void onBegin(CountdownSnapshot snapshot) {
				}

				@Override
				public void onUpdate(CountdownSnapshot snapshot, double remainSeconds, double progress) {
				}

				@Override
				public void onComplete(CountdownSnapshot snapshot) {
				}

				@Override
				public void onStop(CountdownSnapshot snapshot, String reason) {
				}
			};
		}
	}

	public final class ActiveCountdown {
		private final int id;
		private final String title;
		private final int totalSeconds;
		private final long targetEpochMillis;
		private final CountdownListener listener;
		private volatile boolean ended;
		private volatile boolean cancelled;
		private volatile ScheduledFuture<?> future;

		private ActiveCountdown(int id, String title, int totalSeconds, long targetEpochMillis, CountdownListener listener) {
			this.id = id;
			this.title = title;
			this.totalSeconds = totalSeconds;
			this.targetEpochMillis = targetEpochMillis;
			this.listener = listener;
		}

		public int id() {
			return id;
		}

		public String title() {
			return title;
		}

		public CountdownSnapshot snapshot(boolean ended, boolean cancelled) {
			return new CountdownSnapshot(id, title, totalSeconds, targetEpochMillis, ended, cancelled);
		}
	}
}
