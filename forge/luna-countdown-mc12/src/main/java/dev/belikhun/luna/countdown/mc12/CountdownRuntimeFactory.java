package dev.belikhun.luna.countdown.mc12;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.countdown.CountdownMessages;
import dev.belikhun.luna.legacy.countdown.CountdownNotifier;
import dev.belikhun.luna.legacy.countdown.CountdownRuntime;
import dev.belikhun.luna.legacy.countdown.CountdownScheduler;
import dev.belikhun.luna.legacy.countdown.CountdownSnapshot;
import dev.belikhun.luna.legacy.countdown.DefaultCountdownRuntime;
import dev.belikhun.luna.legacy.countdown.ScheduledTask;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Builds the countdown runtime for 1.12.2.
 *
 * The runtime itself is shared and platform-free; this supplies the two things
 * that are not - somewhere for a tick to run, and a way to reach players.
 *
 * Ticking happens off the server thread on a plain executor, because remaining
 * time is derived from the wall clock rather than accumulated per tick: a server
 * that stalls still finishes its countdown when it said it would. Anything that
 * touches players is bounced back onto the server thread with
 * `addScheduledTask`, which is 1.12.2's spelling of what the modern builds call
 * `server.execute`.
 */
public final class CountdownRuntimeFactory {
	private CountdownRuntimeFactory() {
	}

	public static CountdownRuntime create(LunaLogger logger, MinecraftServer server) {
		LunaLogger runtimeLogger = logger.scope("Runtime");

		return new DefaultCountdownRuntime(scheduler(), notifier(runtimeLogger, server), runtimeLogger);
	}

	private static CountdownScheduler scheduler() {
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable task) {
				Thread thread = new Thread(task, "luna-countdown");
				thread.setDaemon(true);
				return thread;
			}
		});

		return new CountdownScheduler() {
			@Override
			public ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayMillis, long periodMillis) {
				final ScheduledFuture<?> future = executor.scheduleAtFixedRate(
					task,
					Math.max(0L, initialDelayMillis),
					Math.max(1L, periodMillis),
					TimeUnit.MILLISECONDS
				);

				return new ScheduledTask() {
					@Override
					public void cancel() {
						// not interrupting: a tick mid-broadcast should finish rather
						// than leave half the players told
						future.cancel(false);
					}
				};
			}
		};
	}

	private static CountdownNotifier notifier(final LunaLogger logger, final MinecraftServer server) {
		return new CountdownNotifier() {
			@Override
			public void begin(CountdownSnapshot snapshot) {
				broadcast(CountdownMessages.countdownBegin(snapshot.title(), snapshot.totalSeconds()));
				logger.audit("Countdown #" + snapshot.id() + " bắt đầu: " + snapshot.title()
					+ " trong " + snapshot.totalSeconds() + "s.");
			}

			@Override
			public void update(CountdownSnapshot snapshot) {
				broadcastActionBar(
					CountdownMessages.countdownBar(snapshot.id(), snapshot.title(), snapshot.remainingSeconds())
				);

				// the last ten seconds, then once a minute: a per-second line for a
				// half-hour countdown would be the only thing in the log
				if (snapshot.remainingSeconds() <= 10D || Math.floor(snapshot.remainingSeconds()) % 60D == 0D) {
					logger.debug("Countdown #" + snapshot.id() + " còn "
						+ String.format(Locale.ROOT, "%.1f", Double.valueOf(snapshot.remainingSeconds())) + "s.");
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
				final ITextComponent line = LunaTextComponents.mini(message);

				server.addScheduledTask(new Runnable() {
					@Override
					public void run() {
						server.getPlayerList().sendMessage(line);
					}
				});
			}

			private void broadcastActionBar(String message) {
				final ITextComponent line = LunaTextComponents.mini(message);

				server.addScheduledTask(new Runnable() {
					@Override
					public void run() {
						for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
							player.sendStatusMessage(line, true);
						}
					}
				});
			}
		};
	}
}
