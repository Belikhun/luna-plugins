package dev.belikhun.luna.countdown.fabric.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricCountdownCommandService {

	private final FabricCountdownService countdownService;
	private final Map<Integer, String> titlesById = new ConcurrentHashMap<>();

	public FabricCountdownCommandService(FabricCountdownService countdownService) {
		this.countdownService = countdownService;
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
			}

			@Override
			public void onUpdate(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds, double progress) {
			}

			@Override
			public void onComplete(FabricCountdownService.CountdownSnapshot snapshot) {
				titlesById.remove(snapshot.id());
			}

			@Override
			public void onStop(FabricCountdownService.CountdownSnapshot snapshot, String reason) {
				titlesById.remove(snapshot.id());
			}
		});
		titlesById.put(active.id(), resolvedTitle);
		String message = "Đã bắt đầu countdown #" + active.id() + " cho \"" + resolvedTitle + "\" trong " + seconds + " giây.";
		return CommandResult.ok(message);
	}

	public CommandResult stop(int id) {
		String title = titlesById.getOrDefault(id, "");
		boolean stopped = countdownService.stop(id, "manual");
		if (!stopped) {
			return CommandResult.fail("Không tìm thấy countdown với ID " + id + ".");
		}
		titlesById.remove(id);
		return CommandResult.ok("Đã dừng countdown #" + id + (title.isBlank() ? "" : " (" + title + ")") + ".");
	}

	public CommandResult stopAll() {
		int count = countdownService.snapshots().size();
		countdownService.stopAll("manual");
		titlesById.clear();
		return CommandResult.ok("Đã dừng " + count + " countdown đang hoạt động.");
	}

	public Collection<FabricCountdownService.CountdownSnapshot> snapshots() {
		return countdownService.snapshots().stream()
			.sorted(Comparator.comparingInt(FabricCountdownService.CountdownSnapshot::id))
			.toList();
	}

	public Map<Integer, String> titlesById() {
		return Map.copyOf(new LinkedHashMap<>(titlesById));
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
