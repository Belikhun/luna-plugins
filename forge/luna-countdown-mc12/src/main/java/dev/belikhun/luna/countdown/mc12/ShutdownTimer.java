package dev.belikhun.luna.countdown.mc12;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.countdown.CountdownMessages;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A scheduled server shutdown, announced as it approaches.
 *
 * One at a time on purpose: a second `/shutdown` while one is pending is
 * refused rather than silently replacing it, because two timers counting to
 * different moments is how a server goes down earlier than anyone was told.
 *
 * **Shutting the server down is a direct call here**, unlike the modern builds.
 * Those probe for `halt`/`stopServer` reflectively because the method has moved
 * across versions; on this line it is `initiateShutdown()`, we compile against
 * MCP, and RFG reobfuscates the call - so a reflective search by name would only
 * be a way to fail at runtime on the SRG names a live server actually has.
 */
public final class ShutdownTimer implements AutoCloseable {
	private final LunaLogger logger;
	private final MinecraftServer server;
	private final ScheduledExecutorService executor;

	private volatile ActiveShutdown activeShutdown;

	public ShutdownTimer(LunaLogger logger, MinecraftServer server) {
		if (server == null) {
			throw new IllegalArgumentException("server");
		}

		this.logger = logger.scope("ShutdownTimer");
		this.server = server;
		this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable task) {
				Thread thread = new Thread(task, "luna-countdown-shutdown");
				thread.setDaemon(true);
				return thread;
			}
		});
	}

	/** @return false when one is already pending */
	public boolean start(int seconds, String reason) {
		if (activeShutdown != null) {
			return false;
		}

		int safeSeconds = Math.max(1, seconds);
		final ActiveShutdown shutdown = new ActiveShutdown(safeSeconds, reason);

		activeShutdown = shutdown;
		broadcastSystem(shutdown.startMessage());

		shutdown.task = executor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				server.addScheduledTask(new Runnable() {
					@Override
					public void run() {
						shutdown.tick();
					}
				});
			}
		}, 0L, 1L, TimeUnit.SECONDS);

		logger.audit("Đã lên lịch tắt máy chủ sau " + safeSeconds + "s reason=" + safe(reason));

		return true;
	}

	/** @return false when nothing was pending */
	public boolean cancel(String reason) {
		ActiveShutdown shutdown = activeShutdown;

		if (shutdown == null) {
			return false;
		}

		activeShutdown = null;
		shutdown.cancel();

		broadcastSystem(CountdownMessages.shutdownCancelled());
		broadcastActionBar("Đã hủy tắt máy chủ.");
		logger.audit("Đã hủy lịch tắt máy chủ.");

		return true;
	}

	public boolean isPending() {
		return activeShutdown != null;
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

	private void broadcastSystem(String message) {
		final ITextComponent line = LunaTextComponents.mini(safe(message));

		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				server.getPlayerList().sendMessage(line);
			}
		});
	}

	private void broadcastActionBar(String message) {
		final ITextComponent line = LunaTextComponents.mini(safe(message));

		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
					player.sendStatusMessage(line, true);
				}
			}
		});
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private final class ActiveShutdown {
		private final int totalSeconds;
		private final long targetAtMillis;
		private final String reason;

		private volatile ScheduledFuture<?> task;

		private ActiveShutdown(int totalSeconds, String reason) {
			this.totalSeconds = totalSeconds;
			this.targetAtMillis = System.currentTimeMillis() + (totalSeconds * 1000L);
			this.reason = Strings.isBlank(reason) ? null : reason.trim();
		}

		/** Runs on the server thread, so it may touch players and stop the server. */
		private void tick() {
			// a cancel that landed between scheduling and running leaves this
			// instance detached; it must not keep announcing
			if (activeShutdown != this) {
				cancel();
				return;
			}

			double remainingSeconds = Math.max(0D, (targetAtMillis - System.currentTimeMillis()) / 1000D);

			broadcastActionBar(CountdownMessages.shutdownBar(Math.max(1D, remainingSeconds), reason));

			if (remainingSeconds > 0D) {
				return;
			}

			activeShutdown = null;
			cancel();

			broadcastSystem(CountdownMessages.shutdownNow());
			logger.warn("Đang yêu cầu tắt máy chủ từ shutdown timer.");
			server.initiateShutdown();
		}

		private String startMessage() {
			return CountdownMessages.shutdownBegin(totalSeconds, reason);
		}

		private void cancel() {
			ScheduledFuture<?> current = task;

			if (current != null) {
				current.cancel(false);
			}
		}
	}
}
