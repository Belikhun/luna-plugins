package dev.belikhun.luna.countdown.fabric.shutdown;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.countdown.fabric.text.Broadcasts;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one scheduled shutdown a server may have pending.
 *
 * Only one is allowed at a time: two timers counting down to the same event give
 * players contradictory numbers, and cancelling would only reach one of them.
 */
public final class FabricShutdownTimer implements AutoCloseable {
	private final LunaLogger logger;
	private final MinecraftServer server;
	private final ScheduledExecutorService executor;
	private volatile ActiveShutdown activeShutdown;

	public FabricShutdownTimer(LunaLogger logger, MinecraftServer server) {
		this.logger = logger.scope("ShutdownTimer");
		this.server = Objects.requireNonNull(server, "server");
		this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-countdown-fabric-shutdown");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Schedule the shutdown. False when one is already pending. */
	public boolean start(int seconds, String reason) {
		if (activeShutdown != null) {
			return false;
		}

		int safeSeconds = Math.max(1, seconds);
		ActiveShutdown shutdown = new ActiveShutdown(safeSeconds, reason);

		activeShutdown = shutdown;

		Broadcasts.chat(server, shutdown.startMessage());
		shutdown.task = executor.scheduleAtFixedRate(shutdown::tick, 0L, 1L, TimeUnit.SECONDS);
		logger.audit("Đã lên lịch tắt máy chủ sau " + safeSeconds + "s reason=" + (reason == null ? "" : reason));

		return true;
	}

	/** Call off the pending shutdown. False when none is pending. */
	public boolean cancel(String reason) {
		ActiveShutdown shutdown = activeShutdown;

		if (shutdown == null) {
			return false;
		}

		activeShutdown = null;
		shutdown.cancel();

		Broadcasts.chat(server, "✔ " + (reason == null ? "" : reason));
		Broadcasts.actionBar(server, "Đã hủy tắt máy chủ.");
		logger.audit("Đã hủy lịch tắt máy chủ.");

		return true;
	}

	@Override
	public void close() {
		ActiveShutdown shutdown = activeShutdown;
		activeShutdown = null;

		if (shutdown != null) {
			shutdown.cancel();
		}

		executor.shutdownNow();
	}

	private static String readableTime(double seconds) {
		return Formatters.compactDuration(Duration.ofSeconds((long) Math.ceil(Math.max(1D, seconds))));
	}

	private final class ActiveShutdown {
		private final int totalSeconds;
		private final long targetAtMillis;
		private final String reason;
		private volatile ScheduledFuture<?> task;

		private ActiveShutdown(int totalSeconds, String reason) {
			this.totalSeconds = totalSeconds;
			this.targetAtMillis = System.currentTimeMillis() + (totalSeconds * 1000L);
			this.reason = reason == null || reason.isBlank() ? null : reason.trim();
		}

		private void tick() {
			if (activeShutdown != this) {
				cancel();
				return;
			}

			double remainingSeconds = Math.max(0D, (targetAtMillis - System.currentTimeMillis()) / 1000D);

			Broadcasts.actionBar(server, progressMessage(remainingSeconds));

			if (remainingSeconds > 0D) {
				return;
			}

			activeShutdown = null;
			cancel();

			Broadcasts.chat(server, "⚠ Đang tắt máy chủ...");
			logger.warn("Đang yêu cầu tắt máy chủ từ shutdown timer.");

			// queued behind the announcement rather than called from here: the
			// server thread runs its tasks in order, so halting through it is what
			// guarantees players are told before the socket closes
			server.execute(() -> server.halt(false));
		}

		private String startMessage() {
			String base = "Máy chủ sẽ tắt sau " + readableTime(totalSeconds) + " nữa!";

			if (reason == null) {
				return base;
			}

			return base + " Lý do: " + reason;
		}

		private String progressMessage(double remainingSeconds) {
			String base = "⚠ TẮT MÁY CHỦ sau " + readableTime(remainingSeconds);

			if (reason == null) {
				return base;
			}

			return base + " (" + reason + ")";
		}

		private void cancel() {
			ScheduledFuture<?> currentTask = task;

			if (currentTask != null) {
				currentTask.cancel(false);
			}
		}
	}
}
