package dev.belikhun.luna.legacy.countdown;

import dev.belikhun.luna.legacy.string.CommandStrings;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.string.MiniText;
import dev.belikhun.luna.legacy.ui.LunaPalette;

/**
 * Every player-facing string luna-countdown's commands produce, as MiniMessage.
 *
 * Shared by paper and the three mod loaders for the same reason
 * {@code MessengerMessages} is: four separate command registrations, one
 * wording. Anything the operator typed is escaped on the way in, since these
 * are parsed as markup.
 */
public final class CountdownMessages {
	private CountdownMessages() {
	}

	public static String notReady() {
		return danger("❌ LunaCountdown chưa sẵn sàng.");
	}

	/** The command root is a parameter: the mod builds register it under two names. */
	public static String countdownUsage(String root) {
		return CommandStrings.usage(
			"/" + root,
			CommandStrings.required("start|stop|stopall", "action")
		);
	}

	public static String countdownStartUsage(String root) {
		return CommandStrings.usage(
			"/" + root,
			CommandStrings.literal("start"),
			CommandStrings.required("length", "time"),
			CommandStrings.optional("message", "text")
		);
	}

	public static String countdownStopUsage(String root) {
		return CommandStrings.usage(
			"/" + root,
			CommandStrings.literal("stop"),
			CommandStrings.required("id", "number")
		);
	}

	public static String shutdownUsage(String root) {
		return CommandStrings.usage(
			"/" + root,
			CommandStrings.required("length", "time"),
			CommandStrings.optional("message", "text")
		);
	}

	public static String invalidTime(String input) {
		return danger("❌ Thời gian không hợp lệ: " + white(input));
	}

	public static String invalidId(String input) {
		return danger("❌ ID không hợp lệ: " + white(input));
	}

	public static String unknownAction(String action) {
		return danger("❌ Hành động " + white(action) + " không tồn tại.");
	}

	public static String countdownNotFound(int id) {
		return danger("❌ Không tìm thấy countdown với ID " + white(String.valueOf(id)));
	}

	public static String countdownStopped(int id) {
		return success("✔ Đã dừng countdown #" + id + ".");
	}

	public static String allCountdownsStopped() {
		return success("✔ Đã dừng toàn bộ countdown đang hoạt động.");
	}

	public static String shutdownAlreadyScheduled() {
		return danger("❌ Tắt máy chủ đã được lên lịch!")
			+ " <white>Hủy bằng <yellow>/shutdown cancel</yellow>.</white>";
	}

	public static String shutdownScheduled(String readableTime) {
		return success("✔ Đã lên lịch tắt máy chủ sau " + readableTime + ".");
	}

	public static String noShutdownScheduled() {
		return danger("❌ Không có lịch tắt máy chủ.");
	}

	public static String shutdownCancelled() {
		return success("✔ Đã hủy tắt máy chủ.");
	}

	/**
	 * A duration, coloured by how far away it is: hours amber, minutes teal,
	 * the last five minutes sky. Players read the colour before the number.
	 */
	public static String readableTime(double seconds) {
		if (seconds / 3600D > 1) {
			return String.format(
				java.util.Locale.ROOT,
				"<color:%s>%.0fh %.2fm</color>",
				LunaPalette.AMBER_300,
				Math.floor(seconds / 3600D),
				(seconds % 3600D) / 60D
			);
		}

		if (seconds > 300D) {
			return String.format(
				java.util.Locale.ROOT,
				"<color:%s>%.0fm %.0fs</color>",
				LunaPalette.TEAL_300,
				Math.floor(seconds / 60D),
				seconds % 60D
			);
		}

		return String.format(java.util.Locale.ROOT, "<color:%s>%.1fs</color>", LunaPalette.SKY_300, seconds);
	}

	public static String countdownBegin(String title, double seconds) {
		return "<white>Sự kiện <green>" + MiniText.escape(title) + "</green> sẽ bắt đầu sau "
			+ readableTime(seconds) + "<white> nữa!</white>";
	}

	/** The bossbar line, rebuilt every tick of the countdown. */
	public static String countdownBar(int id, String title, double remainingSeconds) {
		return "<gray>#" + id + " <green>" + MiniText.escape(title) + "</green> sau "
			+ readableTime(remainingSeconds) + "</gray>";
	}

	public static String countdownStarted(int id, String title) {
		return "<white>Sự kiện <gray>#" + id + " <green>" + MiniText.escape(title)
			+ "</green> đã bắt đầu!</gray></white>";
	}

	public static String countdownCancelled(int id, String title) {
		return "<white>Sự kiện <gray>(#" + id + ")</gray> <light_purple>" + MiniText.escape(title)
			+ "</light_purple> đã bị hủy!</white>";
	}

	public static String shutdownBegin(double seconds, String reason) {
		String head = "<white>Máy chủ sẽ tắt sau " + readableTime(seconds);

		return reason == null || Strings.isBlank(reason)
			? head + "<white> nữa!</white>"
			: head + "<white> nữa! <gray>(lí do: " + MiniText.escape(reason) + ")</gray></white>";
	}

	/** The shutdown bossbar, which stays red for the whole countdown. */
	public static String shutdownBar(double remainingSeconds, String reason) {
		String head = "<color:" + LunaPalette.DANGER_500 + ">⚠⚠⚠ TẮT MÁY CHỦ ⚠⚠⚠</color><white> sau "
			+ readableTime(remainingSeconds);

		return reason == null || Strings.isBlank(reason)
			? head + "</white>"
			: head + " <gray>(" + MiniText.escape(reason) + ")</gray></white>";
	}

	/** The server exposes no shutdown hook this build can call. */
	public static String shutdownUnsupported() {
		return danger("❌ Không thể tự động tắt máy chủ: thiếu API shutdown phù hợp.");
	}

	public static String shutdownNow() {
		return "<yellow>⚠ Đang tắt máy chủ...</yellow>";
	}

	private static String white(String value) {
		return "<white>" + MiniText.escape(value) + "</white>";
	}

	private static String danger(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}

	private static String success(String text) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">" + text + "</color>";
	}
}
