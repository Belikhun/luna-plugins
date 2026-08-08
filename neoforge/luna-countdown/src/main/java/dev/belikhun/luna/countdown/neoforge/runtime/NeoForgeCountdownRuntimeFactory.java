package dev.belikhun.luna.countdown.neoforge.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.countdown.neoforge.model.CountdownSnapshot;

import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the countdown runtime this mod ships.
 *
 * Ticking happens off the server thread on a plain executor, because the
 * remaining time is derived from the wall clock rather than accumulated per
 * tick: a server that stalls still finishes its countdown when it said it would.
 */
public final class NeoForgeCountdownRuntimeFactory {
	private NeoForgeCountdownRuntimeFactory() {
	}

	/**
	 * @param logger the mod's logger, scoped further by the runtime itself
	 * @return the runtime the mod drives its countdowns through
	 */
	public static NeoForgeCountdownRuntime create(LunaLogger logger) {
		LunaLogger runtimeLogger = logger.scope("Runtime");

		return new DefaultNeoForgeCountdownRuntime(createScheduler(), createNotifier(runtimeLogger), runtimeLogger);
	}

	private static NeoForgeCountdownScheduler createScheduler() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-countdown-neoforge");
			thread.setDaemon(true);
			return thread;
		});

		return (task, initialDelayMillis, periodMillis) -> {
			var future = executor.scheduleAtFixedRate(task, Math.max(0L, initialDelayMillis), Math.max(1L, periodMillis), TimeUnit.MILLISECONDS);

			return () -> future.cancel(false);
		};
	}

	private static NeoForgeCountdownNotifier createNotifier(LunaLogger logger) {
		return new NeoForgeCountdownNotifier() {
			@Override
			public void begin(CountdownSnapshot snapshot) {
				broadcast("Sự kiện " + snapshotLabel(snapshot) + " sẽ bắt đầu sau " + readableTime(snapshot.totalSeconds()) + " nữa!");
				logger.audit("Countdown #" + snapshot.id() + " bắt đầu: " + snapshot.title() + " trong " + snapshot.totalSeconds() + "s.");
			}

			@Override
			public void update(CountdownSnapshot snapshot) {
				broadcastActionBar(progressMessage(snapshot));

				if (snapshot.remainingSeconds() <= 10D || Math.floor(snapshot.remainingSeconds()) % 60D == 0D) {
					logger.debug("Countdown #" + snapshot.id() + " còn " + String.format(Locale.ROOT, "%.1f", snapshot.remainingSeconds()) + "s.");
				}
			}

			@Override
			public void complete(CountdownSnapshot snapshot) {
				broadcast("Sự kiện " + snapshotLabel(snapshot) + " đã bắt đầu!");
				logger.success("Countdown #" + snapshot.id() + " đã hoàn tất: " + snapshot.title());
			}

			@Override
			public void cancelled(CountdownSnapshot snapshot, String reason) {
				broadcast("Sự kiện (#" + snapshot.id() + ") " + snapshot.title() + " đã bị hủy! " + safe(reason));
				logger.warn("Countdown #" + snapshot.id() + " đã bị hủy: " + reason);
			}

			private void broadcast(String message) {
				var server = LunaCoreNeoForge.services().server();

				server.execute(() -> server.getPlayerList().broadcastSystemMessage(Component.literal(message), false));
			}

			private void broadcastActionBar(String message) {
				var server = LunaCoreNeoForge.services().server();

				server.execute(() -> {
					Component component = Component.literal(safe(message));

					for (var player : server.getPlayerList().getPlayers()) {
						player.displayClientMessage(component, true);
					}
				});
			}

			private String readableTime(int seconds) {
				return Formatters.compactDuration(Duration.ofSeconds(Math.max(1L, seconds)));
			}

			private String snapshotLabel(CountdownSnapshot snapshot) {
				return "(#" + snapshot.id() + ") " + safe(snapshot.title());
			}

			private String progressMessage(CountdownSnapshot snapshot) {
				return "#" + snapshot.id() + " " + safe(snapshot.title()) + " sau "
					+ readableTime((int) Math.ceil(Math.max(1D, snapshot.remainingSeconds())));
			}

			private String safe(String value) {
				return value == null ? "" : value;
			}
		};
	}
}
