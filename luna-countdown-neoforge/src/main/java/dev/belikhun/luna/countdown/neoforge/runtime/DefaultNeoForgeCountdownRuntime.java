package dev.belikhun.luna.countdown.neoforge.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.countdown.neoforge.model.CountdownSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class DefaultNeoForgeCountdownRuntime implements NeoForgeCountdownRuntime {
	private final NeoForgeCountdownScheduler scheduler;
	private final NeoForgeCountdownNotifier notifier;
	private final LunaLogger logger;
	private final AtomicInteger nextId;
	private final Map<Integer, RunningCountdown> activeCountdowns;

	DefaultNeoForgeCountdownRuntime(NeoForgeCountdownScheduler scheduler, NeoForgeCountdownNotifier notifier, LunaLogger logger) {
		this.scheduler = scheduler;
		this.notifier = notifier;
		this.logger = logger;
		this.nextId = new AtomicInteger(1);
		this.activeCountdowns = new ConcurrentHashMap<>();
	}

	@Override
	public int start(String title, int seconds) {
		int safeSeconds = Math.max(1, seconds);
		int id = nextId.getAndIncrement();
		RunningCountdown countdown = new RunningCountdown(id, title == null || title.isBlank() ? "Sự kiện" : title.trim(), safeSeconds);
		activeCountdowns.put(id, countdown);
		countdown.task = scheduler.scheduleAtFixedRate(countdown::tick, 0L, 1000L);
		notifier.begin(countdown.snapshot(safeSeconds, false));
		logger.audit("Đã tạo countdown #" + id + " title=" + countdown.title + " seconds=" + safeSeconds);
		return id;
	}

	@Override
	public boolean stop(int id, String reason) {
		RunningCountdown countdown = activeCountdowns.remove(id);
		if (countdown == null) {
			return false;
		}

		countdown.cancel();
		notifier.cancelled(countdown.snapshot(countdown.remainingSeconds(), false), reason == null ? "Đã hủy." : reason);
		return true;
	}

	@Override
	public void stopAll(String reason) {
		List<Integer> ids = new ArrayList<>(activeCountdowns.keySet());
		for (Integer id : ids) {
			stop(id, reason);
		}
	}

	@Override
	public List<CountdownSnapshot> activeCountdowns() {
		List<CountdownSnapshot> snapshots = new ArrayList<>();
		for (RunningCountdown countdown : activeCountdowns.values()) {
			snapshots.add(countdown.snapshot(countdown.remainingSeconds(), false));
		}
		return List.copyOf(snapshots);
	}

	@Override
	public void close() {
		stopAll("NeoForge countdown runtime đang tắt.");
	}

	private final class RunningCountdown {
		private final int id;
		private final String title;
		private final int totalSeconds;
		private final long startedAtMillis;
		private volatile NeoForgeScheduledTask task;

		private RunningCountdown(int id, String title, int totalSeconds) {
			this.id = id;
			this.title = title;
			this.totalSeconds = totalSeconds;
			this.startedAtMillis = System.currentTimeMillis();
		}

		private void tick() {
			double remaining = remainingSeconds();
			if (remaining > 0) {
				notifier.update(snapshot(remaining, false));
				return;
			}

			if (activeCountdowns.remove(id, this)) {
				cancel();
				notifier.complete(snapshot(0, true));
			}
		}

		private double remainingSeconds() {
			long elapsedMillis = System.currentTimeMillis() - startedAtMillis;
			return Math.max(0D, (totalSeconds * 1000D - elapsedMillis) / 1000D);
		}

		private CountdownSnapshot snapshot(double remainingSeconds, boolean completed) {
			return new CountdownSnapshot(id, title, totalSeconds, remainingSeconds, completed);
		}

		private void cancel() {
			NeoForgeScheduledTask currentTask = task;
			if (currentTask != null) {
				currentTask.cancel();
				task = null;
			}
		}
	}
}
