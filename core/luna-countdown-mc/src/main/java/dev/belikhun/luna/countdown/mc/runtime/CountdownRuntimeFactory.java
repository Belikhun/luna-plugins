package dev.belikhun.luna.countdown.mc.runtime;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.countdown.mc.model.CountdownSnapshot;

import dev.belikhun.luna.core.api.countdown.CountdownMessages;
import dev.belikhun.luna.core.mc.compat.PlayerMessages;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

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
public final class CountdownRuntimeFactory {
	private CountdownRuntimeFactory() {
	}

	/**
	 * @param logger the mod's logger, scoped further by the runtime itself
	 * @return the runtime the mod drives its countdowns through
	 */
	public static CountdownRuntime create(LunaLogger logger, MinecraftServer server) {
		LunaLogger runtimeLogger = logger.scope("Runtime");

		return new DefaultCountdownRuntime(createScheduler(), createNotifier(runtimeLogger, server), runtimeLogger);
	}

	private static CountdownScheduler createScheduler() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-countdown");
			thread.setDaemon(true);
			return thread;
		});

		return (task, initialDelayMillis, periodMillis) -> {
			var future = executor.scheduleAtFixedRate(task, Math.max(0L, initialDelayMillis), Math.max(1L, periodMillis), TimeUnit.MILLISECONDS);

			return () -> future.cancel(false);
		};
	}

	private static CountdownNotifier createNotifier(LunaLogger logger, MinecraftServer server) {
		return new CountdownNotifier() {
			@Override
			public void begin(CountdownSnapshot snapshot) {
				broadcast(CountdownMessages.countdownBegin(snapshot.title(), snapshot.totalSeconds()));
				logger.audit("Countdown #" + snapshot.id() + " bắt đầu: " + snapshot.title() + " trong " + snapshot.totalSeconds() + "s.");
			}

			@Override
			public void update(CountdownSnapshot snapshot) {
				broadcastActionBar(CountdownMessages.countdownBar(snapshot.id(), snapshot.title(), snapshot.remainingSeconds()));

				if (snapshot.remainingSeconds() <= 10D || Math.floor(snapshot.remainingSeconds()) % 60D == 0D) {
					logger.debug("Countdown #" + snapshot.id() + " còn " + String.format(Locale.ROOT, "%.1f", snapshot.remainingSeconds()) + "s.");
				}
			}

			@Override
			public void complete(CountdownSnapshot snapshot) {
				broadcast(CountdownMessages.countdownStarted(snapshot.id(), snapshot.title()));
				logger.success("Countdown #" + snapshot.id() + " đã hoàn tất: " + snapshot.title());
			}

			@Override
			public void cancelled(CountdownSnapshot snapshot, String reason) {
				broadcast(CountdownMessages.countdownCancelled(snapshot.id(), snapshot.title()));
				logger.warn("Countdown #" + snapshot.id() + " đã bị hủy: " + reason);
			}

			private void broadcast(String message) {
				Component line = LunaTextComponents.mini(message);

				server.execute(() -> server.getPlayerList().broadcastSystemMessage(line, false));
			}

			private void broadcastActionBar(String message) {

				Component component = LunaTextComponents.mini(message);

				server.execute(() -> {

					for (var player : server.getPlayerList().getPlayers()) {
						PlayerMessages.actionBar(player, component);
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
