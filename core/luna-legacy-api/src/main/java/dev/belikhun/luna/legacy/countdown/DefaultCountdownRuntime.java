package dev.belikhun.luna.legacy.countdown;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Several countdowns at once, each ticking once a second.
 *
 * Identical in behaviour to the modern build's runtime, and platform-free for
 * the same reason: everything here is arithmetic and bookkeeping, and the two
 * places a server version could matter - where a tick runs, and how a message
 * reaches a player - are the {@link CountdownScheduler} and
 * {@link CountdownNotifier} it is handed.
 *
 * Remaining time is computed from a start timestamp rather than decremented per
 * tick, so a scheduler that drifts or misses a tick still finishes at the right
 * moment instead of running long by however much it drifted.
 */
public final class DefaultCountdownRuntime implements CountdownRuntime {
	/** What a countdown is called when the caller gave no title. */
	private static final String DEFAULT_TITLE = "Sự kiện";

	private final CountdownScheduler scheduler;
	private final CountdownNotifier notifier;
	private final LunaLogger logger;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final Map<Integer, RunningCountdown> activeCountdowns = new ConcurrentHashMap<Integer, RunningCountdown>();

	public DefaultCountdownRuntime(CountdownScheduler scheduler, CountdownNotifier notifier, LunaLogger logger) {
		this.scheduler = scheduler;
		this.notifier = notifier;
		this.logger = logger;
	}

	@Override
	public int start(String title, int seconds) {
		int safeSeconds = Math.max(1, seconds);
		int id = nextId.getAndIncrement();

		RunningCountdown countdown = new RunningCountdown(
			id,
			Strings.isBlank(title) ? DEFAULT_TITLE : title.trim(),
			safeSeconds
		);

		activeCountdowns.put(Integer.valueOf(id), countdown);

		countdown.task = scheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				countdown.tick();
			}
		}, 0L, 1000L);

		notifier.begin(countdown.snapshot(safeSeconds, false));
		logger.audit("Đã tạo countdown #" + id + " title=" + countdown.title + " seconds=" + safeSeconds);

		return id;
	}

	@Override
	public boolean stop(int id, String reason) {
		RunningCountdown countdown = activeCountdowns.remove(Integer.valueOf(id));

		if (countdown == null) {
			return false;
		}

		countdown.cancel();
		notifier.cancelled(
			countdown.snapshot(countdown.remainingSeconds(), false),
			reason == null ? "Đã hủy." : reason
		);

		return true;
	}

	@Override
	public void stopAll(String reason) {
		// over a copy of the keys: stop() mutates the map, and iterating it live
		// would be a concurrent modification on every platform but this one's map
		for (Integer id : new ArrayList<Integer>(activeCountdowns.keySet())) {
			stop(id.intValue(), reason);
		}
	}

	@Override
	public List<CountdownSnapshot> activeCountdowns() {
		List<CountdownSnapshot> snapshots = new ArrayList<CountdownSnapshot>();

		for (RunningCountdown countdown : activeCountdowns.values()) {
			snapshots.add(countdown.snapshot(countdown.remainingSeconds(), false));
		}

		return Collections.unmodifiableList(snapshots);
	}

	@Override
	public void close() {
		stopAll("Máy chủ đang tắt.");
	}

	private final class RunningCountdown {
		private final int id;
		private final String title;
		private final int totalSeconds;
		private final long startedAtMillis;
		private volatile ScheduledTask task;

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

			// remove-if-still-present, so a stop() racing the final tick cannot make
			// a countdown both complete and cancel
			if (activeCountdowns.remove(Integer.valueOf(id), this)) {
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
			ScheduledTask currentTask = task;

			if (currentTask != null) {
				currentTask.cancel();
				task = null;
			}
		}
	}
}
