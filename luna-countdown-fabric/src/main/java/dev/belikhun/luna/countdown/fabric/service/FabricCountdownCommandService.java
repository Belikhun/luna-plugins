package dev.belikhun.luna.countdown.fabric.service;

import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricCountdownCommandService {
	private static final long SHUTDOWN_DELAY_MILLIS = 3000L;

	private final FabricCountdownService countdownService;
	private final Runnable shutdownAction;
	private final Map<Integer, String> titlesById = new ConcurrentHashMap<>();
	private volatile Integer shutdownCountdownId;

	public FabricCountdownCommandService(FabricCountdownService countdownService) {
		this(countdownService, () -> {
		});
	}

	public FabricCountdownCommandService(FabricCountdownService countdownService, Runnable shutdownAction) {
		this.countdownService = countdownService;
		this.shutdownAction = shutdownAction == null ? () -> {
		} : shutdownAction;
	}

	public CommandResult start(String durationInput, String title) {
		int seconds = FabricCountdownFormat.parseTime(durationInput);
		if (seconds <= 0) {
			return CommandResult.fail("Thời gian không hợp lệ: " + (durationInput == null ? "" : durationInput));
		}

		String resolvedTitle = (title == null || title.isBlank()) ? "Sự Kiện Kết Thúc" : title;
		FabricCountdownService.ActiveCountdown active = countdownService.start(resolvedTitle, seconds, new FabricCountdownService.CountdownListener() {
			@Override
			public void onBegin(FabricCountdownService.CountdownSnapshot snapshot) {
				countdownService.broadcastMessage("Sự kiện " + resolvedTitle + " sẽ bắt đầu sau " + FabricCountdownFormat.readablePlainTime(seconds) + " nữa!");
			}

			@Override
			public void onUpdate(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds, double progress) {
			}

			@Override
			public void onComplete(FabricCountdownService.CountdownSnapshot snapshot) {
				titlesById.remove(snapshot.id());
				countdownService.broadcastMessage("Sự kiện #" + snapshot.id() + " " + resolvedTitle + " đã bắt đầu!");
			}

			@Override
			public void onStop(FabricCountdownService.CountdownSnapshot snapshot, String reason) {
				titlesById.remove(snapshot.id());
				countdownService.broadcastMessage("Sự kiện (#" + snapshot.id() + ") " + resolvedTitle + " đã bị hủy!");
			}
		});
		titlesById.put(active.id(), resolvedTitle);
		String message = "Đã bắt đầu countdown #" + active.id() + " cho \"" + resolvedTitle + "\" trong " + seconds + " giây.";
		return CommandResult.ok(message);
	}

	public CommandResult stop(int id) {
		if (isActiveShutdown(id)) {
			return CommandResult.fail("Dùng /shutdown cancel để hủy lịch tắt máy chủ.");
		}

		String title = titlesById.getOrDefault(id, "");
		String reason = title.isBlank() ? "Đã hủy countdown #" + id : "Đã hủy bỏ " + title;
		boolean stopped = countdownService.stop(id, reason);
		if (!stopped) {
			return CommandResult.fail("Không tìm thấy countdown với ID " + id + ".");
		}
		titlesById.remove(id);
		return CommandResult.ok("Đã dừng countdown #" + id + (title.isBlank() ? "" : " (" + title + ")") + ".");
	}

	public CommandResult stopAll() {
		int count = titlesById.size();
		for (Integer id : titlesById.keySet()) {
			countdownService.stop(id, "Đã hủy countdown");
		}
		titlesById.clear();
		return CommandResult.ok("Đã dừng " + count + " countdown đang hoạt động.");
	}

	public CommandResult scheduleShutdown(String durationInput, String reason) {
		Integer activeShutdownId = shutdownCountdownId;
		if (activeShutdownId != null && isCountdownActive(activeShutdownId)) {
			return CommandResult.fail("Tắt máy chủ đã được lên lịch! Hủy bằng /shutdown cancel.");
		}

		int seconds = FabricCountdownFormat.parseTime(durationInput);
		if (seconds <= 0) {
			return CommandResult.fail("Thời gian không hợp lệ: " + (durationInput == null ? "" : durationInput));
		}

		String shutdownReason = normalizeReason(reason);
		FabricCountdownService.ActiveCountdown active = countdownService.start(
			"TẮT MÁY CHỦ",
			seconds,
			new FabricCountdownService.CountdownListener() {
				@Override
				public void onBegin(FabricCountdownService.CountdownSnapshot snapshot) {
					String message = shutdownReason.isBlank()
						? "Máy chủ sẽ tắt sau " + FabricCountdownFormat.readablePlainTime(seconds) + " nữa!"
						: "Máy chủ sẽ tắt sau " + FabricCountdownFormat.readablePlainTime(seconds) + " nữa! (lí do: " + shutdownReason + ")";
					countdownService.broadcastMessage(message);
				}

				@Override
				public void onUpdate(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds, double progress) {
				}

				@Override
				public void onComplete(FabricCountdownService.CountdownSnapshot snapshot) {
					shutdownCountdownId = null;
					countdownService.broadcastMessage("⚠ Đang tắt máy chủ...");
					countdownService.scheduleTask(SHUTDOWN_DELAY_MILLIS, shutdownAction);
				}

				@Override
				public void onStop(FabricCountdownService.CountdownSnapshot snapshot, String stopReason) {
					if (shutdownCountdownId != null && shutdownCountdownId == snapshot.id()) {
						shutdownCountdownId = null;
					}
					countdownService.broadcastMessage("✔ Đã hủy tắt máy chủ.");
				}
			},
			shutdownDisplay(shutdownReason)
		);
		shutdownCountdownId = active.id();
		return CommandResult.ok("Đã lên lịch tắt máy chủ sau " + seconds + " giây.");
	}

	public CommandResult cancelShutdown() {
		Integer activeShutdownId = shutdownCountdownId;
		if (activeShutdownId == null || !isCountdownActive(activeShutdownId)) {
			shutdownCountdownId = null;
			return CommandResult.fail("Không có lịch tắt máy chủ.");
		}

		boolean stopped = countdownService.stop(activeShutdownId, "Đã Hủy Tắt Máy Chủ!");
		shutdownCountdownId = null;
		return stopped ? CommandResult.ok("Đã hủy tắt máy chủ.") : CommandResult.fail("Không có lịch tắt máy chủ.");
	}

	public boolean hasScheduledShutdown() {
		Integer activeShutdownId = shutdownCountdownId;
		return activeShutdownId != null && isCountdownActive(activeShutdownId);
	}

	public Collection<FabricCountdownService.CountdownSnapshot> snapshots() {
		return countdownService.snapshots().stream()
			.sorted(Comparator.comparingInt(FabricCountdownService.CountdownSnapshot::id))
			.toList();
	}

	public Map<Integer, String> titlesById() {
		return Map.copyOf(new LinkedHashMap<>(titlesById));
	}

	private boolean isActiveShutdown(int id) {
		Integer activeShutdownId = shutdownCountdownId;
		return activeShutdownId != null && activeShutdownId == id && isCountdownActive(id);
	}

	private boolean isCountdownActive(int id) {
		for (FabricCountdownService.CountdownSnapshot snapshot : countdownService.snapshots()) {
			if (snapshot.id() == id) {
				return true;
			}
		}
		return false;
	}

	private String normalizeReason(String reason) {
		return reason == null ? "" : reason.trim();
	}

	private FabricCountdownService.CountdownDisplay shutdownDisplay(String reason) {
		return new FabricCountdownService.CountdownDisplay() {
			@Override
			public Component runningTitle(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds) {
				String suffix = reason.isBlank() ? "" : " (" + reason + ")";
				return Component.literal("⚠⚠⚠ TẮT MÁY CHỦ ⚠⚠⚠ sau " + FabricCountdownFormat.readablePlainTime(remainSeconds) + suffix);
			}

			@Override
			public Component completedTitle(FabricCountdownService.CountdownSnapshot snapshot) {
				return Component.literal("Đang Tắt Máy Chủ...");
			}

			@Override
			public Component stoppedTitle(FabricCountdownService.CountdownSnapshot snapshot, String stopReason) {
				return Component.literal("Đã Hủy Tắt Máy Chủ!");
			}

			@Override
			public BossEvent.BossBarColor completedColor() {
				return BossEvent.BossBarColor.YELLOW;
			}

			@Override
			public BossEvent.BossBarColor stoppedColor() {
				return BossEvent.BossBarColor.GREEN;
			}
		};
	}

	public record CommandResult(boolean success, String message) {
		public static CommandResult ok(String message) {
			return new CommandResult(true, message);
		}

		public static CommandResult fail(String message) {
			return new CommandResult(false, message);
		}
	}
}
