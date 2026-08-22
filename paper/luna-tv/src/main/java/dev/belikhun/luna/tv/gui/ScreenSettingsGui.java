package dev.belikhun.luna.tv.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import dev.belikhun.luna.core.api.gui.AnvilInputManager;
import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.string.MiniText;
import dev.belikhun.luna.core.api.ui.LunaUi;

import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;
import dev.belikhun.luna.tv.screen.TvScreen;

import net.kyori.adventure.text.Component;

/**
 * The inventory face of a screen's settings.
 *
 * Everything here calls the same ScreenManager verbs as the chat commands, so
 * the two stay interchangeable; the GUI only adds discoverability. Each action
 * re-renders the view in place, which is what makes toggles feel like toggles.
 */
public final class ScreenSettingsGui {

	private final JavaPlugin plugin;
	private final ScreenManager screens;
	private final GuiManager guis;
	private final AnvilInputManager anvil;

	public ScreenSettingsGui(JavaPlugin plugin, ScreenManager screens, GuiManager guis, AnvilInputManager anvil) {
		this.plugin = plugin;
		this.screens = screens;
		this.guis = guis;
		this.anvil = anvil;
	}

	/**
	 * Opens the screen list: one map per screen, click to open its settings.
	 *
	 * @param player who is browsing
	 */
	public void openList(Player player) {
		List<ScreenInstance> all = new ArrayList<>(screens.instances());
		int rows = Math.max(1, (all.size() + 8) / 9);
		GuiView view = new GuiView(Math.min(54, rows * 9), LunaUi.guiTitleBreadcrumb("Luna TV", "Màn hình"));

		for (int index = 0; index < all.size() && index < 54; index++) {
			ScreenInstance instance = all.get(index);
			TvScreen screen = instance.screen();

			view.setItem(index, LunaUi.item(Material.FILLED_MAP,
				"<white><b>" + MiniText.escape(screen.name()) + "</b></white>",
				List.of(
					LunaUi.mini("<gray>" + screen.mapsWide() + "×" + screen.mapsHigh()
						+ " bản đồ · " + stateLabel(instance) + "</gray>"),
					LunaUi.mini("<dark_gray>" + MiniText.escape(trim(screen.url())) + "</dark_gray>"),
					Component.empty(),
					LunaUi.mini("<yellow>Bấm để mở cài đặt</yellow>"))),
				(clicker, click, view0) -> open(clicker, instance));
		}

		guis.track(view);
		view.open(player);
	}

	/**
	 * Opens one screen's settings panel.
	 *
	 * @param player who is editing
	 * @param instance the screen
	 */
	public void open(Player player, ScreenInstance instance) {
		// the instance may have been removed while the previous view was open
		Optional<ScreenInstance> current = screens.find(instance.name());

		if (current.isEmpty()) {
			player.sendRichMessage("<red>❌ Màn hình không còn tồn tại.</red>");

			return;
		}

		TvScreen screen = instance.screen();
		GuiView view = new GuiView(27, LunaUi.guiTitleBreadcrumb("Luna TV", screen.name()));

		view.setItem(4, LunaUi.item(Material.FILLED_MAP,
			"<white><b>" + MiniText.escape(screen.name()) + "</b></white>",
			List.of(
				LunaUi.mini("<gray>Kích thước: " + screen.mapsWide() + "×" + screen.mapsHigh()
					+ " bản đồ (" + screen.pixelWidth() + "×" + screen.pixelHeight() + "px)</gray>"),
				LunaUi.mini("<gray>Trạng thái: " + stateLabel(instance) + "</gray>"),
				LunaUi.mini("<dark_gray>" + MiniText.escape(trim(screen.url())) + "</dark_gray>"))));

		boolean powered = instance.powered();

		view.setItem(3, LunaUi.item(powered ? Material.REDSTONE_TORCH : Material.LEVER,
			powered ? "<green>Nguồn: đang bật</green>" : "<red>Nguồn: đã tắt</red>",
			List.of(LunaUi.mini(powered
				? "<gray>Bấm để tắt: đóng trình duyệt, màn hình đen</gray>"
				: "<gray>Bấm để bật: mở trình duyệt và luồng hình</gray>"))),
			(clicker, click, view0) -> {
				ScreenManager.Outcome outcome = screens.power(instance, !powered);

				if (!outcome.success()) {
					clicker.sendRichMessage("<yellow>⚠ " + MiniText.escape(outcome.reason()) + "</yellow>");
				}

				open(clicker, instance);
			});

		view.setItem(10, LunaUi.item(Material.WRITABLE_BOOK,
			"<aqua>Đổi trang web</aqua>",
			List.of(LunaUi.mini("<gray>Nhập địa chỉ mới qua bàn đe</gray>"))),
			(clicker, click, view0) -> promptUrl(clicker, instance));

		view.setItem(11, LunaUi.item(Material.CLOCK,
			"<aqua>Tải lại trang</aqua>",
			List.of(LunaUi.mini("<gray>Như bấm F5 trong trình duyệt</gray>"))),
			(clicker, click, view0) -> {
				if (instance.browser() != null) {
					instance.browser().reload();
				}

				clicker.sendRichMessage("<green>✔ Đã tải lại '" + MiniText.escape(screen.name()) + "'.</green>");
				open(clicker, instance);
			});

		boolean audioOn = screen.audio();

		view.setItem(12, LunaUi.item(audioOn ? Material.NOTE_BLOCK : Material.BARRIER,
			audioOn ? "<green>Âm thanh: đang phát</green>" : "<red>Âm thanh: tắt</red>",
			List.of(LunaUi.mini("<gray>Bấm để " + (audioOn ? "tắt" : "bật")
				+ " phát tiếng vào voice chat</gray>"))),
			(clicker, click, view0) -> {
				ScreenManager.Outcome outcome = screens.audio(instance, !audioOn);

				if (!outcome.success()) {
					clicker.sendRichMessage("<red>❌ " + MiniText.escape(outcome.reason()) + "</red>");
				}

				open(clicker, instance);
			});

		boolean stereoOn = screen.stereo();

		view.setItem(17, LunaUi.item(stereoOn ? Material.JUKEBOX : Material.NOTE_BLOCK,
			stereoOn ? "<green>Âm thanh: stereo</green>" : "<gray>Âm thanh: mono</gray>",
			List.of(
				LunaUi.mini("<gray>Stereo dùng hai kênh đặt ở hai mép màn hình,</gray>"),
				LunaUi.mini("<gray>nên chỉ nghe rõ khi đứng trước màn hình.</gray>"),
				LunaUi.mini("<yellow>Bấm để chuyển sang " + (stereoOn ? "mono" : "stereo") + "</yellow>"))),
			(clicker, click, view0) -> {
				screens.stereo(instance, !stereoOn);
				open(clicker, instance);
			});

		boolean scrollOn = screen.scroll();

		view.setItem(26, LunaUi.item(scrollOn ? Material.LEAD : Material.STRING,
			scrollOn ? "<green>Shift + lăn: cuộn trang</green>" : "<gray>Lăn chuột: đổi ô đồ</gray>",
			List.of(
				LunaUi.mini("<gray>Bật: giữ Shift, nhìn vào màn hình rồi lăn chuột</gray>"),
				LunaUi.mini("<gray>để cuộn ngay tại chỗ đang ngắm; thanh đồ giữ nguyên.</gray>"),
				LunaUi.mini("<gray>Không giữ Shift thì lăn chuột vẫn đổi ô đồ.</gray>"),
				LunaUi.mini("<gray>Tắt: lăn chuột đổi ô đồ như bình thường.</gray>"),
				LunaUi.mini("<yellow>Bấm để " + (scrollOn ? "tắt" : "bật") + "</yellow>"))),
			(clicker, click, view0) -> {
				screens.scroll(instance, !scrollOn);
				open(clicker, instance);
			});

		view.setItem(18, LunaUi.item(Material.BUCKET,
			"<aqua>Xoá dữ liệu duyệt web</aqua>",
			List.of(
				LunaUi.mini("<gray>Bấm trái: xoá cookie, cache và bộ nhớ trang.</gray>"),
				LunaUi.mini("<gray>Giữ Shift + bấm phải: xoá cả profile</gray>"),
				LunaUi.mini("<gray>(mật khẩu đã lưu) rồi mở lại trình duyệt.</gray>"))),
			(clicker, click, view0) -> {
				boolean deep = click.isRightClick() && click.isShiftClick();
				ScreenManager.Outcome outcome = screens.clearBrowserData(instance, deep);

				if (!outcome.success()) {
					clicker.sendRichMessage("<red>❌ " + MiniText.escape(outcome.reason()) + "</red>");
				} else {
					clicker.sendRichMessage("<green>✔ Đã xoá "
						+ (deep ? "toàn bộ profile" : "cookie, cache và bộ nhớ trang") + ".</green>");
				}

				open(clicker, instance);
			});

		view.setItem(13, LunaUi.item(Material.REPEATER,
			"<aqua>Âm lượng: " + screen.volume() + "%</aqua>",
			List.of(
				LunaUi.mini("<gray>Bấm trái: +10 · bấm phải: −10</gray>"))),
			(clicker, click, view0) -> {
				int step = click.isRightClick() ? -10 : 10;

				screens.volume(instance, screen.volume() + step);
				open(clicker, instance);
			});

		boolean locked = screen.locked();

		view.setItem(14, LunaUi.item(locked ? Material.IRON_DOOR : Material.OAK_DOOR,
			locked ? "<red>Đang khoá</red>" : "<green>Đang mở</green>",
			List.of(LunaUi.mini("<gray>Khoá thì chỉ người có quyền mới bấm được màn hình</gray>"))),
			(clicker, click, view0) -> {
				screens.locked(instance, !locked);
				open(clicker, instance);
			});

		view.setItem(15, LunaUi.item(Material.SPYGLASS,
			"<aqua>Độ phân giải: 1/" + screen.scale() + "</aqua>",
			List.of(
				LunaUi.mini("<gray>1 = nét nhất, 2-4 = nhẹ máy hơn</gray>"),
				LunaUi.mini("<gray>Video tự chọn chất lượng theo mức này</gray>"),
				LunaUi.mini("<yellow>Bấm để chuyển mức</yellow>"))),
			(clicker, click, view0) -> {
				int next = screen.scale() >= 4 ? 1 : screen.scale() + 1;

				screens.scale(instance, next);
				clicker.sendRichMessage("<green>✔ Độ phân giải của '" + MiniText.escape(screen.name())
					+ "' giờ là 1/" + next + ".</green>");
				open(clicker, instance);
			});

		view.setItem(16, LunaUi.item(Material.ENDER_EYE,
			"<aqua>Gửi lại hình</aqua>",
			List.of(LunaUi.mini("<gray>Vẽ lại màn hình cho mọi người xem</gray>"))),
			(clicker, click, view0) -> {
				resend(instance);
				clicker.sendRichMessage("<green>✔ Đã gửi lại '" + MiniText.escape(screen.name()) + "'.</green>");
			});

		int ownFps = screen.fps();
		String fpsLabel = ownFps == 0
			? "theo chung (" + screens.effectiveFps(screen) + ")"
			: ownFps + " fps";

		view.setItem(19, LunaUi.item(Material.COMPARATOR,
			"<aqua>FPS: " + fpsLabel + "</aqua>",
			List.of(
				LunaUi.mini("<gray>Cao hơn = mượt hơn, tốn CPU và mạng hơn</gray>"),
				LunaUi.mini("<yellow>Bấm để chuyển: chung → 5 → 10 → 15 → 20 → 30</yellow>"))),
			(clicker, click, view0) -> {
				int next = switch (screen.fps()) {
					case 0 -> 5;
					case 5 -> 10;
					case 10 -> 15;
					case 15 -> 20;
					case 20 -> 30;
					default -> 0;
				};

				screens.fps(instance, next);
				open(clicker, instance);
			});

		int ownMegabits = screen.maxMegabits();
		String bandwidthLabel = ownMegabits == 0
			? "theo chung (" + screens.effectiveMegabits(screen) + " Mbit)"
			: ownMegabits + " Mbit";

		view.setItem(20, LunaUi.item(Material.HOPPER,
			"<aqua>Băng thông: " + bandwidthLabel + "</aqua>",
			List.of(
				LunaUi.mini("<gray>Trần dữ liệu hình gửi tới mỗi người xem</gray>"),
				LunaUi.mini("<gray>Quá trần thì bỏ khung hình, không vỡ hình</gray>"),
				LunaUi.mini("<yellow>Bấm: chung → 20 → 36 → 50 → 100 → 200</yellow>"))),
			(clicker, click, view0) -> {
				int next = switch (screen.maxMegabits()) {
					case 0 -> 20;
					case 20 -> 36;
					case 36 -> 50;
					case 50 -> 100;
					case 100 -> 200;
					default -> 0;
				};

				screens.maxMegabits(instance, next);
				open(clicker, instance);
			});

		view.setItem(23, LunaUi.item(Material.GLOWSTONE_DUST,
			"<aqua>Độ sáng: " + screen.brightness() + "%</aqua>",
			List.of(
				LunaUi.mini("<gray>Bảng màu bản đồ rất hẹp, cảnh tối mất hết chi tiết;</gray>"),
				LunaUi.mini("<gray>kéo sáng lên trước khi đổi màu sẽ lấy lại được.</gray>"),
				LunaUi.mini("<yellow>Bấm trái: +20% · bấm phải: −20%</yellow>"))),
			(clicker, click, view0) -> {
				int step = click.isRightClick() ? -20 : 20;

				screens.brightness(instance, screen.brightness() + step);
				open(clicker, instance);
			});

		String dither = screen.converter();
		Material ditherIcon = switch (dither) {
			case "" -> Material.GRAY_DYE;
			case "DIRECT" -> Material.WHITE_DYE;
			case "ORDERED" -> Material.LIME_DYE;
			default -> Material.CYAN_DYE;
		};

		view.setItem(24, LunaUi.item(ditherIcon,
			"<aqua>Tán màu: " + screens.converterLabel(screen) + "</aqua>",
			List.of(
				LunaUi.mini("<gray>Tắt: lấy màu gần nhất, chữ và mảng phẳng sạch.</gray>"),
				LunaUi.mini("<gray>Bật: tán màu nhanh, chuyển sắc mượt hơn mà</gray>"),
				LunaUi.mini("<gray>gần như không tốn thêm CPU.</gray>"),
				LunaUi.mini("<gray>Floyd: mượt nhất nhưng tốn CPU nhất.</gray>"),
				LunaUi.mini("<yellow>Bấm trái: chung → tắt → bật → floyd</yellow>"),
				LunaUi.mini("<yellow>Bấm phải: đổi hoa văn (bayer, a1-a4)</yellow>"))),
			(clicker, click, view0) -> {
				if (click.isRightClick()) {
					// picking a pattern is a statement of intent, so the mode
					// follows it to ORDERED even from off/floyd
					String next = switch (screen.ditherPattern()) {
						case "" -> "bayer";
						case "bayer" -> "a1";
						case "a1" -> "a2";
						case "a2" -> "a3";
						case "a3" -> "a4";
						default -> "";
					};

					screens.dither(instance, "ORDERED", next);
				} else {
					String next = switch (screen.converter()) {
						case "" -> "DIRECT";
						case "DIRECT" -> "ORDERED";
						case "ORDERED" -> "FLOYD_STEINBERG";
						default -> "";
					};

					screens.converter(instance, next);
				}

				open(clicker, instance);
			});

		view.setItem(21, LunaUi.item(Material.ENDER_PEARL,
			"<aqua>Dịch chuyển tới màn hình</aqua>", List.of()),
			(clicker, click, view0) -> {
				Location center = screens.center(instance);

				if (center != null) {
					clicker.teleport(center);
				}
			});

		view.setItem(22, LunaUi.item(Material.ARROW,
			"<gray>Về danh sách</gray>", List.of()),
			(clicker, click, view0) -> openList(clicker));

		view.setItem(25, LunaUi.item(Material.TNT,
			"<red>Xoá màn hình</red>",
			List.of(LunaUi.mini("<gray>Giữ Shift và bấm để xác nhận</gray>"))),
			(clicker, click, view0) -> {
				if (!click.isShiftClick()) {
					clicker.sendRichMessage("<yellow>⚠ Giữ Shift và bấm để xoá thật.</yellow>");

					return;
				}

				screens.remove(screen.name());
				clicker.sendRichMessage("<green>✔ Đã xoá màn hình '" + MiniText.escape(screen.name()) + "'.</green>");
				clicker.closeInventory();
			});

		guis.track(view);
		view.open(player);
	}

	private void promptUrl(Player player, ScreenInstance instance) {
		player.closeInventory();
		anvil.open(player, AnvilInputManager.request(
			LunaUi.guiTitle("Địa chỉ trang web"),
			(who, text) -> {
				String url = text.trim();

				if (!url.startsWith("http://") && !url.startsWith("https://")) {
					url = "https://" + url;
				}

				screens.navigate(instance, url);
				who.sendRichMessage("<green>✔ Đã mở</green> <white>" + MiniText.escape(url) + "</white>");
				plugin.getServer().getScheduler().runTask(plugin, () -> open(who, instance));
			},
			who -> plugin.getServer().getScheduler().runTask(plugin, () -> open(who, instance)))
			.withInitialText(instance.screen().url() == null ? "https://" : instance.screen().url()));
	}

	private void resend(ScreenInstance instance) {
		for (Player online : List.copyOf(plugin.getServer().getOnlinePlayers())) {
			if (instance.viewers().contains(online.getUniqueId()) && instance.display() != null) {
				instance.display().despawn(online);
			}
		}

		instance.viewers().clear();
		instance.placeholderStale();
	}

	private static String stateLabel(ScreenInstance instance) {
		return instance.state() == ScreenState.RUNNING ? "đang chạy" : instance.state().label();
	}

	private static String trim(String url) {
		if (url == null) {
			return "";
		}

		return url.length() > 40 ? url.substring(0, 37) + "…" : url;
	}
}
