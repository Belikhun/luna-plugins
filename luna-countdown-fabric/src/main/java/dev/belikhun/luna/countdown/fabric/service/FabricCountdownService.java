package dev.belikhun.luna.countdown.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

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
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FabricCountdownService implements AutoCloseable {
	private static final long UPDATE_PERIOD_MILLIS = 100L;
	private static final double AUTO_CLOSE_AFTER_SECONDS = 5.0d;

	private final LunaLogger logger;
	private final Supplier<MinecraftServer> serverSupplier;
	private final ScheduledExecutorService scheduler;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final Map<Integer, ActiveCountdown> activeById = new ConcurrentHashMap<>();
	private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

	public FabricCountdownService(LunaLogger logger) {
		this(logger, () -> null);
	}

	public FabricCountdownService(LunaLogger logger, Supplier<MinecraftServer> serverSupplier) {
		this.logger = logger.scope("Countdown");
		this.serverSupplier = serverSupplier == null ? () -> null : serverSupplier;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-countdown-fabric");
			thread.setDaemon(true);
			return thread;
		});
	}

	public ActiveCountdown start(String title, int seconds, CountdownListener listener) {
		return start(title, seconds, listener, CountdownDisplay.standard());
	}

	public ActiveCountdown start(String title, int seconds, CountdownListener listener, CountdownDisplay display) {
		if (seconds <= 0) {
			throw new IllegalArgumentException("seconds must be > 0");
		}

		CountdownListener safeListener = listener == null ? CountdownListener.noop() : listener;
		CountdownDisplay safeDisplay = display == null ? CountdownDisplay.standard() : display;
		int id = nextId.getAndIncrement();
		long targetEpochMillis = System.currentTimeMillis() + (seconds * 1000L);
		ActiveCountdown countdown = new ActiveCountdown(id, title == null ? "" : title, seconds, targetEpochMillis, safeListener, safeDisplay);
		activeById.put(id, countdown);
		safeListener.onBegin(countdown.snapshot(false, false));
		syncBossBarStart(countdown);
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
		syncBossBarStop(countdown, reason);
		scheduleTask((long) (AUTO_CLOSE_AFTER_SECONDS * 1000L), () -> removeBossBar(countdown));
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

	public void scheduleTask(long delayMillis, Runnable task) {
		if (task == null) {
			return;
		}

		scheduler.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
	}

	public void handlePlayerJoin(UUID playerId) {
		if (playerId == null) {
			return;
		}
		onlinePlayers.add(playerId);
		executeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return;
			}

			for (ActiveCountdown countdown : activeById.values()) {
				ServerBossEvent bar = countdown.bossBar;
				if (bar != null) {
					bar.addPlayer(player);
				}
			}
		});
	}

	public void handlePlayerQuit(UUID playerId) {
		if (playerId == null) {
			return;
		}
		onlinePlayers.remove(playerId);
		executeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return;
			}

			for (ActiveCountdown countdown : activeById.values()) {
				ServerBossEvent bar = countdown.bossBar;
				if (bar != null) {
					bar.removePlayer(player);
				}
			}
		});
	}

	public int onlinePlayerCount() {
		return onlinePlayers.size();
	}

	public void broadcastMessage(String message) {
		String plain = FabricCountdownFormat.stripMiniMessage(message);
		if (plain.isBlank()) {
			return;
		}

		executeOnServer(server -> {
			Component text = Component.literal(plain);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.sendSystemMessage(text);
			}
		});
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
		double progress = clamp(remainSeconds / countdown.totalSeconds);

		if (remainSeconds >= 0d) {
			countdown.listener.onUpdate(countdown.snapshot(false, false), remainSeconds, progress);
			syncBossBarUpdate(countdown, remainSeconds, progress);
			return;
		}

		if (!countdown.ended) {
			countdown.ended = true;
			countdown.listener.onComplete(countdown.snapshot(true, false));
			syncBossBarComplete(countdown);
		}

		if (remainSeconds < -AUTO_CLOSE_AFTER_SECONDS) {
			activeById.remove(countdown.id);
			cancelFuture(countdown.future);
			removeBossBar(countdown);
		}
	}

	private void syncBossBarStart(ActiveCountdown countdown) {
		executeOnServer(server -> {
			ServerBossEvent bar = new ServerBossEvent(
				countdown.display.runningTitle(countdown.snapshot(false, false), countdown.totalSeconds),
				BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.PROGRESS
			);
			bar.setProgress(1.0f);
			countdown.bossBar = bar;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				bar.addPlayer(player);
			}
		});
	}

	private void syncBossBarUpdate(ActiveCountdown countdown, double remainSeconds, double progress) {
		executeOnServer(server -> {
			ServerBossEvent bar = countdown.bossBar;
			if (bar == null) {
				return;
			}

			bar.setName(countdown.display.runningTitle(countdown.snapshot(false, false), remainSeconds));
			bar.setProgress((float) clamp(progress));
			if (remainSeconds > 60d) {
				bar.setColor(BossEvent.BossBarColor.GREEN);
			} else if (remainSeconds > 10d) {
				bar.setColor(BossEvent.BossBarColor.YELLOW);
			} else {
				bar.setColor(BossEvent.BossBarColor.RED);
			}
		});
	}

	private void syncBossBarComplete(ActiveCountdown countdown) {
		executeOnServer(server -> {
			ServerBossEvent bar = countdown.bossBar;
			if (bar == null) {
				return;
			}

			bar.setName(countdown.display.completedTitle(countdown.snapshot(true, false)));
			bar.setColor(countdown.display.completedColor());
			bar.setProgress(1.0f);
		});
	}

	private void syncBossBarStop(ActiveCountdown countdown, String reason) {
		executeOnServer(server -> {
			ServerBossEvent bar = countdown.bossBar;
			if (bar == null) {
				return;
			}

			bar.setName(countdown.display.stoppedTitle(countdown.snapshot(false, true), reason));
			bar.setColor(countdown.display.stoppedColor());
			bar.setProgress(1.0f);
		});
	}

	private void removeBossBar(ActiveCountdown countdown) {
		executeOnServer(server -> {
			ServerBossEvent bar = countdown.bossBar;
			if (bar == null) {
				return;
			}

			bar.removeAllPlayers();
			countdown.bossBar = null;
		});
	}

	private void executeOnServer(Consumer<MinecraftServer> action) {
		MinecraftServer server = serverSupplier.get();
		if (server == null || action == null) {
			return;
		}

		server.execute(() -> action.accept(server));
	}

	private double clamp(double progress) {
		if (progress < 0d) {
			return 0d;
		}
		if (progress > 1d) {
			return 1d;
		}
		return progress;
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

	public interface CountdownDisplay {
		Component runningTitle(CountdownSnapshot snapshot, double remainSeconds);

		Component completedTitle(CountdownSnapshot snapshot);

		Component stoppedTitle(CountdownSnapshot snapshot, String reason);

		default BossEvent.BossBarColor completedColor() {
			return BossEvent.BossBarColor.BLUE;
		}

		default BossEvent.BossBarColor stoppedColor() {
			return BossEvent.BossBarColor.PURPLE;
		}

		static CountdownDisplay standard() {
			return new CountdownDisplay() {
				@Override
				public Component runningTitle(CountdownSnapshot snapshot, double remainSeconds) {
					return Component.literal("#" + snapshot.id() + " " + snapshot.title() + " sau " + FabricCountdownFormat.readablePlainTime(remainSeconds));
				}

				@Override
				public Component completedTitle(CountdownSnapshot snapshot) {
					return Component.literal("#" + snapshot.id() + " " + snapshot.title() + " đã bắt đầu!");
				}

				@Override
				public Component stoppedTitle(CountdownSnapshot snapshot, String reason) {
					String plainReason = FabricCountdownFormat.stripMiniMessage(reason);
					if (plainReason.isBlank()) {
						plainReason = "Đã hủy bỏ " + snapshot.title();
					}
					return Component.literal(plainReason);
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
		private final CountdownDisplay display;
		private volatile boolean ended;
		private volatile boolean cancelled;
		private volatile ScheduledFuture<?> future;
		private volatile ServerBossEvent bossBar;

		private ActiveCountdown(int id, String title, int totalSeconds, long targetEpochMillis, CountdownListener listener, CountdownDisplay display) {
			this.id = id;
			this.title = title;
			this.totalSeconds = totalSeconds;
			this.targetEpochMillis = targetEpochMillis;
			this.listener = listener;
			this.display = display;
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
