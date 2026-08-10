package dev.belikhun.luna.countdown.mc.shutdown;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.countdown.mc.model.CountdownSnapshot;
import dev.belikhun.luna.core.api.countdown.CountdownMessages;
import dev.belikhun.luna.core.mc.compat.PlayerMessages;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ShutdownTimer implements AutoCloseable {
	private final LunaLogger logger;
	private final MinecraftServer server;
	private final ScheduledExecutorService executor;
	private volatile ActiveShutdown activeShutdown;

	public ShutdownTimer(LunaLogger logger, MinecraftServer server) {
		this.logger = logger.scope("ShutdownTimer");
		this.server = Objects.requireNonNull(server, "server");
		this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-countdown-shutdown");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean start(int seconds, String reason) {
		if (activeShutdown != null) {
			return false;
		}

		int safeSeconds = Math.max(1, seconds);
		ActiveShutdown shutdown = new ActiveShutdown(safeSeconds, reason);
		activeShutdown = shutdown;
		broadcastSystem(shutdown.startMessage());
		shutdown.task = executor.scheduleAtFixedRate(() -> server.execute(shutdown::tick), 0L, 1L, TimeUnit.SECONDS);
		logger.audit("Đã lên lịch tắt máy chủ sau " + safeSeconds + "s reason=" + safe(reason));
		return true;
	}

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
		Component line = LunaTextComponents.mini(safe(message));

		server.execute(() -> server.getPlayerList().broadcastSystemMessage(line, false));
	}

	private void broadcastActionBar(String message) {
		server.execute(() -> {
			Component component = LunaTextComponents.mini(safe(message));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				PlayerMessages.actionBar(player, component);
			}
		});
	}

	private void runShutdown() {
		logger.warn("Đang yêu cầu tắt máy chủ NeoForge từ shutdown timer.");
		invokeShutdown(server);
	}

	private void invokeShutdown(MinecraftServer server) {
		for (String methodName : new String[] {"halt", "stopServer"}) {
			try {
				var booleanMethod = server.getClass().getMethod(methodName, boolean.class);
				booleanMethod.invoke(server, false);
				return;
			} catch (NoSuchMethodException ignored) {
			} catch (ReflectiveOperationException exception) {
				logger.error("Không thể gọi " + methodName + "(boolean) để tắt máy chủ.", exception);
				return;
			}

			try {
				var noArgMethod = server.getClass().getMethod(methodName);
				noArgMethod.invoke(server);
				return;
			} catch (NoSuchMethodException ignored) {
			} catch (ReflectiveOperationException exception) {
				logger.error("Không thể gọi " + methodName + "() để tắt máy chủ.", exception);
				return;
			}
		}

		logger.error("Không tìm thấy API shutdown phù hợp trên MinecraftServer.");
		broadcastSystem(CountdownMessages.shutdownUnsupported());
	}

	private String readableTime(int seconds) {
		return Formatters.compactDuration(Duration.ofSeconds(Math.max(1L, seconds)));
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private final class ActiveShutdown {
		private final int totalSeconds;
		private final long targetAtMillis;
		private final String reason;
		private volatile java.util.concurrent.ScheduledFuture<?> task;

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

			long remainingMillis = targetAtMillis - System.currentTimeMillis();
			double remainingSeconds = Math.max(0D, remainingMillis / 1000D);
			broadcastActionBar(progressMessage(remainingSeconds));
			if (remainingSeconds > 0D) {
				return;
			}

			activeShutdown = null;
			cancel();
			broadcastSystem(CountdownMessages.shutdownNow());
			runShutdown();
		}

		private String startMessage() {
			return CountdownMessages.shutdownBegin(totalSeconds, reason);
		}

		private String progressMessage(double remainingSeconds) {
			return CountdownMessages.shutdownBar(Math.max(1D, remainingSeconds), reason);
		}

		private void cancel() {
			if (task != null) {
				task.cancel(false);
			}
		}
	}
}
