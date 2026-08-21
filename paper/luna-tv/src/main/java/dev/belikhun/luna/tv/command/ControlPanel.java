package dev.belikhun.luna.tv.command;

import org.bukkit.command.CommandSender;

import dev.belikhun.luna.core.api.string.MiniText;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenState;

/**
 * The chat remote control.
 *
 * A screen is across the room, so the controls cannot be on it. They are chat
 * components instead: every button is a click-to-run command, and the panel is
 * re-sent after each one so what the reader sees is the screen's current state
 * rather than the state when they opened it.
 */
public final class ControlPanel {

	private static final int URL_LIMIT = 46;

	private ControlPanel() {
	}

	/**
	 * Sends the panel for one screen.
	 *
	 * @param sender who to send it to
	 * @param instance the screen being controlled
	 * @param config the plugin configuration, for the quick pages
	 * @param audioAvailable whether audio can be turned on at all
	 */
	public static void send(
		CommandSender sender,
		ScreenInstance instance,
		TvConfig config,
		boolean audioAvailable
	) {
		String name = instance.name();

		sender.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300 + ">─── <bold>Luna TV</bold> · "
			+ MiniText.escape(name) + " ───</color>");
		sender.sendRichMessage(status(instance));
		sender.sendRichMessage(urlRow(instance));
		sender.sendRichMessage(navRow(name, instance));
		sender.sendRichMessage(audioRow(name, instance, audioAvailable));
		boolean powered = instance.powered();

		sender.sendRichMessage(inputRow(name)
			+ " " + button("⚙ GUI", "run_command", "/lunatv gui " + name,
				"Mở bảng cài đặt đầy đủ", LunaPalette.GOLD_300)
			+ " " + button(powered ? "⏻ Tắt" : "⏻ Bật", "run_command",
				"/lunatv power " + name + (powered ? " off" : " on"),
				powered ? "Tắt trình duyệt, màn hình đen" : "Mở trình duyệt",
				powered ? LunaPalette.DANGER_500 : LunaPalette.SUCCESS_500));

		if (!config.presets().isEmpty()) {
			sender.sendRichMessage(presetRow(name, config));
		}
	}

	private static String status(ScreenInstance instance) {
		String state = switch (instance.state()) {
			case RUNNING -> "<color:" + LunaPalette.SUCCESS_500 + ">đang chạy</color>";
			case STARTING -> "<color:" + LunaPalette.WARNING_500 + ">đang mở</color>";
			case CRASHED -> "<color:" + LunaPalette.DANGER_500 + ">lỗi</color>";
			case SUSPENDED -> "<color:" + LunaPalette.NEUTRAL_300 + ">chờ thế giới</color>";
			case OFF -> "<color:" + LunaPalette.NEUTRAL_300 + ">đã tắt</color>";
		};

		String size = instance.screen().mapsWide() + "×" + instance.screen().mapsHigh();

		return "<gray>Trạng thái: " + state + "<gray> · " + size + " bản đồ · "
			+ instance.viewers().size() + " người xem</gray>";
	}

	private static String urlRow(ScreenInstance instance) {
		String url = instance.screen().url();
		String shown = url == null || url.isBlank() ? "(trống)" : shorten(url);

		return "<gray>Trang: <white>" + MiniText.escape(shown) + "</white></gray> "
			+ button("✎ Đổi", "suggest_command", "/lunatv url " + instance.name() + " ",
				"Gõ địa chỉ mới vào ô chat", LunaPalette.PRIMARY_500);
	}

	private static String navRow(String name, ScreenInstance instance) {
		String lock = instance.screen().locked()
			? button("🔓 Mở khoá", "run_command", "/lunatv lock " + name + " off",
				"Cho mọi người bấm vào màn hình", LunaPalette.SUCCESS_500)
			: button("🔒 Khoá", "run_command", "/lunatv lock " + name + " on",
				"Chỉ người có quyền được bấm", LunaPalette.WARNING_500);

		return button("⏪ Lùi", "run_command", "/lunatv back " + name, "Trang trước", LunaPalette.PRIMARY_500)
			+ " " + button("⏩ Tới", "run_command", "/lunatv forward " + name, "Trang sau", LunaPalette.PRIMARY_500)
			+ " " + button("⟳ Tải lại", "run_command", "/lunatv refresh " + name, "Tải lại trang", LunaPalette.PRIMARY_500)
			+ " " + lock;
	}

	private static String audioRow(String name, ScreenInstance instance, boolean audioAvailable) {
		if (!audioAvailable && !instance.screen().audio()) {
			return "<gray>Âm thanh: <color:" + LunaPalette.NEUTRAL_300 + ">không khả dụng</color> "
				+ "<gray>(</gray>" + button("xem lý do", "run_command", "/lunatv status",
					"Xem chẩn đoán", LunaPalette.NEUTRAL_300) + "<gray>)</gray></gray>";
		}

		String toggle = instance.screen().audio()
			? button("🔇 Tắt tiếng", "run_command", "/lunatv audio " + name + " off",
				"Ngừng phát vào voice chat", LunaPalette.DANGER_500)
			: button("🔊 Bật tiếng", "run_command", "/lunatv audio " + name + " on",
				"Phát tiếng của trang vào voice chat", LunaPalette.SUCCESS_500);

		int volume = instance.screen().volume();

		return "<gray>Âm thanh: </gray>" + toggle
			+ " <gray>·</gray> " + button("−", "run_command", "/lunatv volume " + name + " " + (volume - 10),
				"Giảm 10%", LunaPalette.NEUTRAL_300)
			+ " <white>" + volume + "%</white> "
			+ button("+", "run_command", "/lunatv volume " + name + " " + (volume + 10),
				"Tăng 10%", LunaPalette.NEUTRAL_300);
	}

	private static String inputRow(String name) {
		return button("⌨ Nhập chữ", "suggest_command", "/lunatv type " + name + " ",
				"Gõ nội dung vào trang", LunaPalette.PRIMARY_500)
			+ " " + button("↵ Enter", "run_command", "/lunatv key " + name + " enter",
				"Gửi phím Enter", LunaPalette.NEUTRAL_300)
			+ " " + button("⌫ Xoá", "run_command", "/lunatv key " + name + " backspace",
				"Gửi phím Backspace", LunaPalette.NEUTRAL_300)
			+ " " + button("⇥ Tab", "run_command", "/lunatv key " + name + " tab",
				"Gửi phím Tab", LunaPalette.NEUTRAL_300);
	}

	private static String presetRow(String name, TvConfig config) {
		StringBuilder row = new StringBuilder("<gray>Trang nhanh: </gray>");

		for (TvConfig.Preset preset : config.presets()) {
			row.append(button(preset.name(), "run_command",
				"/lunatv url " + name + " " + preset.url(),
				preset.url(), LunaPalette.SKY_500)).append(' ');
		}

		return row.toString().trim();
	}

	/**
	 * One clickable button.
	 *
	 * The command is escaped for the MiniMessage single-quoted argument form, the
	 * same way CommandStrings does it: a quote or backslash in a URL would
	 * otherwise end the tag early and print the rest as text.
	 */
	private static String button(String label, String action, String command, String hover, String color) {
		String escaped = command.replace("\\", "\\\\").replace("'", "\\'");

		return "<click:" + action + ":'" + escaped + "'>"
			+ "<hover:show_text:'<gray>" + MiniText.escape(hover) + "</gray>'>"
			+ "<color:" + color + ">[" + label + "]</color>"
			+ "</hover></click>";
	}

	private static String shorten(String url) {
		if (url.length() <= URL_LIMIT) {
			return url;
		}

		return url.substring(0, URL_LIMIT - 1) + "…";
	}

	/** Whether a state is worth offering the control panel for. */
	public static boolean controllable(ScreenState state) {
		return state == ScreenState.RUNNING;
	}
}
