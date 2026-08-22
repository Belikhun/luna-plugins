package dev.belikhun.luna.tv.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.MiniText;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.tv.LunaTvPlugin;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.audio.AudioService;
import dev.belikhun.luna.tv.gui.ScreenSettingsGui;
import dev.belikhun.luna.tv.input.RedstoneListener;
import dev.belikhun.luna.tv.input.WandTool;
import dev.belikhun.luna.tv.panel.TouchPanelService;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.browser.ChromiumProcess;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;

/**
 * /lunatv, the whole operator surface.
 *
 * Every mutating verb needs {@code lunatv.control}; clicking a screen does not,
 * which is the split the plugin is built around. A verb that acts on a screen
 * re-sends the control panel afterwards, so using a panel button leaves the
 * reader looking at fresh state rather than at what they clicked.
 */
public final class LunaTvCommand implements BasicCommand {

	private static final String PERMISSION = "lunatv.control";

	private static final List<String> SUBCOMMANDS = List.of(
		"audio", "back", "clear", "control", "create", "debug", "forward", "gui", "info",
		"bandwidth", "brightness", "cleanup", "dither", "fps", "key", "list", "lock", "panel", "power", "redstone", "refresh", "reload", "remove", "resend", "scale",
		"scroll", "status", "stereo", "teleport", "type", "url", "volume", "wand");

	private static final List<String> KEYS = List.of("enter", "backspace", "tab", "escape",
		"up", "down", "left", "right", "space", "home", "end", "pageup", "pagedown");
	private static final List<String> TOGGLES = List.of("on", "off");

	private final LunaTvPlugin plugin;
	private final ScreenManager screens;
	private final AudioService audio;

	private final WandTool wand;
	private final ScreenSettingsGui gui;
	private final RedstoneListener redstone;
	private final TouchPanelService panels;

	public LunaTvCommand(LunaTvPlugin plugin, ScreenManager screens, AudioService audio,
			WandTool wand, ScreenSettingsGui gui, RedstoneListener redstone, TouchPanelService panels) {
		this.wand = wand;
		this.gui = gui;
		this.redstone = redstone;
		this.panels = panels;
		this.plugin = plugin;
		this.screens = screens;
		this.audio = audio;
	}

	@Override
	public String permission() {
		return PERMISSION;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();

		if (!sender.hasPermission(PERMISSION)) {
			sender.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length == 0) {
			usage(sender);
			return;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "create" -> create(sender, args);
			case "remove" -> remove(sender, args);
			case "list" -> list(sender);
			case "info" -> info(sender, args);
			case "control" -> control(sender, args);
			case "url" -> url(sender, args);
			case "back" -> browserAction(sender, args, CdpBrowser::back);
			case "forward" -> browserAction(sender, args, CdpBrowser::forward);
			case "refresh" -> refresh(sender, args);
			case "lock" -> lock(sender, args);
			case "audio" -> audio(sender, args);
			case "volume" -> volume(sender, args);
			case "type" -> type(sender, args);
			case "key" -> key(sender, args);
			case "teleport" -> teleport(sender, args);
			case "resend" -> resend(sender, args);
			case "status" -> status(sender);
			case "wand" -> giveWand(sender);
			case "power" -> power(sender, args);
			case "redstone" -> redstoneLink(sender, args);
			case "panel" -> panel(sender, args);
			case "cleanup" -> cleanup(sender);
			case "gui" -> openGui(sender, args);
			case "scale" -> scale(sender, args);
			case "fps" -> fps(sender, args);
			case "brightness" -> brightness(sender, args);
			case "dither" -> dither(sender, args);
			case "stereo" -> stereo(sender, args);
			case "scroll" -> scroll(sender, args);
			case "clear" -> clearData(sender, args);
			case "bandwidth" -> bandwidth(sender, args);
			case "debug" -> debug(sender, args);
			case "reload" -> reload(sender);
			default -> usage(sender);
		}
	}

	private void usage(CommandSender sender) {
		sender.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300 + ">─── <bold>Luna TV</bold> ───</color>");
		sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv create <tên> <x1> <y1> <z1> <x2> <y2> <z2> <hướng> [url]"));
		sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv control <tên>"));
		sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv list"));
		sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv status"));
	}

	private void create(CommandSender sender, String[] args) {
		// --world lets the console create a screen too; a player's own world is the
		// default, because standing in front of the wall is how this is normally used
		String worldName = optionValue(args, "--world");
		String[] positional = withoutOption(args, "--world");
		World world;

		if (worldName != null) {
			world = Bukkit.getWorld(worldName);

			if (world == null) {
				sender.sendRichMessage("<red>❌ Không có thế giới '" + MiniText.escape(worldName) + "'.</red>");
				return;
			}
		} else if (sender instanceof Player player) {
			world = player.getWorld();
		} else {
			sender.sendRichMessage("<red>❌ Từ console cần chỉ rõ thế giới: <white>--world <tên></white></red>");
			return;
		}

		args = positional;

		// the wand path: a complete selection plus at most a name and an url means
		// the corners and the facing come from the world, not the command line
		if (sender instanceof Player player && args.length >= 2 && args.length <= 3) {
			WandTool.Selection selection = wand.selection(player);

			if (selection == null || !selection.complete()) {
				sender.sendRichMessage("<red>❌ Chưa chọn đủ hai góc bằng đũa.</red> "
					+ "<gray>Lấy đũa bằng</gray> <white>/lunatv wand</white><gray>, "
					+ "hoặc dùng dạng đầy đủ với toạ độ.</gray>");

				return;
			}

			BlockFace toward = WandTool.facingToward(selection, player);

			if (toward == null) {
				sender.sendRichMessage("<red>❌ Hai góc phải nằm trên một mặt phẳng "
					+ "(một trục trùng nhau).</red>");

				return;
			}

			String selectionUrl = args.length > 2 ? args[2] : plugin.config().homepage();
			ScreenManager.Outcome fromWand = screens.create(
				args[1], selection.world(),
				selection.cornerA(), selection.cornerB(),
				toward, selectionUrl, sender.getName());

			if (!fromWand.success()) {
				sender.sendRichMessage("<red>❌ " + MiniText.escape(fromWand.reason()) + "</red>");

				return;
			}

			wand.clear(player);
			sender.sendRichMessage("<green>✔ Đã tạo màn hình '" + MiniText.escape(args[1])
				+ "' quay về hướng " + toward.name() + ". Đang mở trình duyệt…</green>");

			return;
		}

		if (args.length < 9) {
			sender.sendRichMessage("<red>❌ Thiếu tham số.</red>");
			sender.sendRichMessage(CommandStrings.syntaxRaw(
				"/lunatv create <tên> <x1> <y1> <z1> <x2> <y2> <z2> <hướng> [url] [--world <tên>]"));
			return;
		}

		Integer[] numbers = new Integer[6];

		for (int index = 0; index < 6; index++) {
			numbers[index] = parseInt(args[2 + index]);

			if (numbers[index] == null) {
				sender.sendRichMessage("<red>❌ Toạ độ không hợp lệ: " + MiniText.escape(args[2 + index]) + "</red>");
				return;
			}
		}

		BlockFace facing = parseFacing(args[8]);

		if (facing == null) {
			sender.sendRichMessage("<red>❌ Hướng không hợp lệ: " + MiniText.escape(args[8])
				+ " (dùng " + String.join(", ", ScreenManager.FACINGS) + ")</red>");
			return;
		}

		String url = args.length > 9 ? args[9] : plugin.config().homepage();

		ScreenManager.Outcome outcome = screens.create(
			args[1], world,
			new BlockVector(numbers[0], numbers[1], numbers[2]),
			new BlockVector(numbers[3], numbers[4], numbers[5]),
			facing, url, sender.getName());

		if (!outcome.success()) {
			sender.sendRichMessage("<red>❌ " + MiniText.escape(outcome.reason()) + "</red>");
			return;
		}

		sender.sendRichMessage("<green>✔ Đã tạo màn hình '" + MiniText.escape(args[1])
			+ "'. Đang mở trình duyệt…</green>");
	}

	private void remove(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv remove <tên>"));
			return;
		}

		if (!screens.remove(args[1])) {
			sender.sendRichMessage("<red>❌ Không có màn hình tên '" + MiniText.escape(args[1]) + "'.</red>");
			return;
		}

		sender.sendRichMessage("<green>✔ Đã xoá màn hình '" + MiniText.escape(args[1]) + "'.</green>");
	}

	private void list(CommandSender sender) {
		Collection<ScreenInstance> all = screens.instances();

		if (all.isEmpty()) {
			sender.sendRichMessage("<gray>ℹ Chưa có màn hình nào. Tạo bằng "
				+ "<white>/lunatv create</white>.</gray>");
			return;
		}

		sender.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300 + ">─── <bold>Màn hình Luna TV</bold> ("
			+ all.size() + ") ───</color>");

		for (ScreenInstance instance : all) {
			sender.sendRichMessage("<gray>· </gray><click:run_command:'/lunatv control "
				+ instance.name() + "'><hover:show_text:'<gray>Mở bảng điều khiển</gray>'>"
				+ "<color:" + LunaPalette.PRIMARY_500 + "><underlined>" + MiniText.escape(instance.name())
				+ "</underlined></color></hover></click> <gray>· " + stateWord(instance)
				+ " · " + instance.screen().mapsWide() + "×" + instance.screen().mapsHigh()
				+ (instance.screen().audio() ? " · 🔊" : "")
				+ (instance.screen().locked() ? " · 🔒" : "") + "</gray>");
		}
	}

	private void info(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		CdpBrowser browser = instance.browser();

		sender.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300 + ">─── <bold>"
			+ MiniText.escape(instance.name()) + "</bold> ───</color>");
		sender.sendRichMessage("<gray>Trạng thái: <white>" + stateWord(instance) + "</white></gray>");
		sender.sendRichMessage("<gray>Trang: <white>" + MiniText.escape(String.valueOf(instance.screen().url()))
			+ "</white></gray>");
		sender.sendRichMessage("<gray>Thế giới: <white>" + MiniText.escape(instance.screen().world())
			+ "</white> · kích thước <white>" + instance.screen().mapsWide() + "×"
			+ instance.screen().mapsHigh() + "</white> bản đồ ("
			+ instance.screen().pixelWidth() + "×" + instance.screen().pixelHeight() + "px)</gray>");
		sender.sendRichMessage("<gray>Người xem: <white>" + instance.viewers().size()
			+ "</white> · âm thanh <white>" + (instance.screen().audio() ? "bật" : "tắt")
			+ "</white> · âm lượng <white>" + instance.screen().volume()
			+ "%</white> · " + (instance.screen().locked() ? "đã khoá" : "mở") + "</gray>");

		if (browser != null) {
			sender.sendRichMessage("<gray>Trình duyệt: pid <white>" + browser.pid()
				+ "</white> · cổng CDP <white>" + browser.debugPort() + "</white></gray>");
			sender.sendRichMessage("<gray>Khung hình: giải mã <white>" + browser.framesDecoded()
				+ "</white> · đã gửi <white>" + instance.framesPushed()
				+ "</white> · bỏ qua <white>" + browser.framesDropped()
				+ "</white> · thiếu băng thông <white>" + instance.budgetSkips()
				+ "</white></gray>");

			long fullFrame = (long) instance.screen().mapsWide() * instance.screen().mapsHigh() * 128 * 128;
			int megabits = screens.effectiveMegabits(instance.screen());

			sender.sendRichMessage("<gray>Khung hình toàn màn hình: <white>"
				+ (fullFrame / 1024 / 1024) + " MB</white> · ngân sách <white>" + megabits
				+ " Mbit/s</white> → tối đa <white>"
				+ (megabits * 1_000_000L / 8 / Math.max(1, fullFrame)) + " fps</white> khi cả màn hình đổi</gray>");
		}

		if (instance.failure() != null) {
			sender.sendRichMessage("<color:" + LunaPalette.DANGER_500 + ">Lỗi: "
				+ MiniText.escape(instance.failure()) + "</color>");
		}

		String audioFailure = audio.failure(instance.name());

		if (audioFailure != null) {
			sender.sendRichMessage("<color:" + LunaPalette.WARNING_500 + ">Âm thanh: "
				+ MiniText.escape(audioFailure) + "</color>");
		}

		sender.sendRichMessage("<gray>Tạo bởi <white>" + MiniText.escape(instance.screen().createdBy())
			+ "</white></gray>");
	}

	private void control(CommandSender sender, String[] args) {
		require(sender, args).ifPresent(instance -> panel(sender, instance));
	}

	private void url(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		if (args.length < 3) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv url <tên> <địa chỉ>"));
			return;
		}

		String url = normalizeUrl(args[2]);

		if (url == null) {
			sender.sendRichMessage("<red>❌ Chỉ mở được địa chỉ http:// hoặc https://</red>");
			return;
		}

		screens.navigate(found.get(), url);
		sender.sendRichMessage("<green>✔ Đã mở <white>" + MiniText.escape(url) + "</white></green>");
		panel(sender, found.get());
	}

	private void browserAction(CommandSender sender, String[] args, java.util.function.Consumer<CdpBrowser> action) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		CdpBrowser browser = found.get().browser();

		if (browser == null) {
			sender.sendRichMessage("<red>❌ Màn hình chưa có trình duyệt.</red>");
			return;
		}

		action.accept(browser);
		panel(sender, found.get());
	}

	private void refresh(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		CdpBrowser browser = instance.browser();

		// a crashed screen wants a whole new browser, not a page reload
		if (browser == null || instance.state() != ScreenState.RUNNING) {
			screens.launchBrowser(instance);
			sender.sendRichMessage("<green>✔ Đang mở lại trình duyệt…</green>");
			return;
		}

		browser.reload();
		sender.sendRichMessage("<green>✔ Đã tải lại trang.</green>");
		panel(sender, instance);
	}

	private void lock(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean locked = args.length > 2 ? "on".equalsIgnoreCase(args[2]) : !instance.screen().locked();

		screens.locked(instance, locked);
		sender.sendRichMessage("<green>✔ Màn hình đã " + (locked ? "khoá" : "mở khoá") + ".</green>");
		panel(sender, instance);
	}

	private void audio(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean on = args.length > 2 ? "on".equalsIgnoreCase(args[2]) : !instance.screen().audio();
		ScreenManager.Outcome outcome = screens.audio(instance, on);

		if (!outcome.success()) {
			sender.sendRichMessage("<red>❌ Không bật được tiếng: "
				+ MiniText.escape(outcome.reason()) + "</red>");
			return;
		}

		sender.sendRichMessage("<green>✔ Đã " + (on ? "bật" : "tắt") + " tiếng cho màn hình.</green>");
		panel(sender, instance);
	}

	/** Splits a screen's sound into two positioned channels, or rejoins it. */
	private void stereo(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean on = args.length > 2 ? "on".equalsIgnoreCase(args[2]) : !instance.screen().stereo();

		screens.stereo(instance, on);
		sender.sendRichMessage("<green>✔ Âm thanh của '" + MiniText.escape(instance.name()) + "': "
			+ (on ? "stereo (hai loa ở hai mép màn hình)" : "mono (một nguồn ở giữa)") + ".</green>");

		if (on) {
			sender.sendRichMessage("<gray>Đứng trước màn hình mới nghe rõ hai bên.</gray>");
		}
	}

	/** Turns wheel scrolling on or off for one screen. */
	private void scroll(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean on = args.length > 2 ? "on".equalsIgnoreCase(args[2]) : !instance.screen().scroll();

		screens.scroll(instance, on);

		if (on) {
			sender.sendRichMessage("<green>✔ '" + MiniText.escape(instance.name())
				+ "': giữ Shift rồi lăn chuột để cuộn trang.</green>");
			sender.sendRichMessage("<gray>Không giữ Shift thì lăn chuột vẫn đổi ô đồ như thường.</gray>");

			return;
		}

		sender.sendRichMessage("<green>✔ '" + MiniText.escape(instance.name())
			+ "': lăn chuột đổi ô đồ như bình thường, không cuộn trang.</green>");
	}

	/** Wipes a screen's browsing data; --sau also deletes the profile. */
	private void clearData(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean deep = args.length > 2 && ("sau".equalsIgnoreCase(args[2])
			|| "--sau".equalsIgnoreCase(args[2])
			|| "deep".equalsIgnoreCase(args[2])
			|| "all".equalsIgnoreCase(args[2]));

		ScreenManager.Outcome outcome = screens.clearBrowserData(instance, deep);

		if (!outcome.success()) {
			sender.sendRichMessage("<red>❌ " + MiniText.escape(outcome.reason()) + "</red>");

			return;
		}

		if (deep) {
			sender.sendRichMessage("<green>✔ Đã xoá toàn bộ profile của '"
				+ MiniText.escape(instance.name()) + "' và mở lại trình duyệt.</green>");

			return;
		}

		sender.sendRichMessage("<green>✔ Đã xoá cookie, cache và bộ nhớ trang của '"
			+ MiniText.escape(instance.name()) + "'.</green>");
		sender.sendRichMessage("<gray>Muốn xoá cả mật khẩu đã lưu và profile: /lunatv clear "
			+ MiniText.escape(instance.name()) + " sau</gray>");
	}

	private void volume(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		if (args.length < 3) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv volume <tên> <0-100>"));
			return;
		}

		Integer value = parseInt(args[2]);

		if (value == null) {
			sender.sendRichMessage("<red>❌ Âm lượng phải là số.</red>");
			return;
		}

		screens.volume(found.get(), value);
		sender.sendRichMessage("<green>✔ Âm lượng: <white>"
			+ found.get().screen().volume() + "%</white></green>");
		panel(sender, found.get());
	}

	private void type(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		if (args.length < 3) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv type <tên> <nội dung>"));
			return;
		}

		CdpBrowser browser = found.get().browser();

		if (browser == null) {
			sender.sendRichMessage("<red>❌ Màn hình chưa có trình duyệt.</red>");
			return;
		}

		String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));

		browser.type(text);
		sender.sendRichMessage("<green>✔ Đã gõ <white>" + MiniText.escape(text) + "</white></green>");
	}

	private void key(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		if (args.length < 3 || !KEYS.contains(args[2].toLowerCase(Locale.ROOT))) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv key <tên> <"
				+ String.join("|", KEYS) + ">"));
			return;
		}

		CdpBrowser browser = found.get().browser();

		if (browser == null) {
			sender.sendRichMessage("<red>❌ Màn hình chưa có trình duyệt.</red>");
			return;
		}

		browser.key(args[2]);
		sender.sendRichMessage("<green>✔ Đã gửi phím <white>" + args[2] + "</white></green>");
	}

	private void teleport(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Chỉ người chơi mới dịch chuyển được.</red>");
			return;
		}

		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		Location center = screens.center(found.get());

		if (center == null) {
			sender.sendRichMessage("<red>❌ Thế giới của màn hình chưa được tải.</red>");
			return;
		}

		// stand back from the wall by the screen's own facing, looking at it
		BlockFace facing = found.get().screen().facing();
		Location target = center.clone().add(
			facing.getModX() * 6.0, facing.getModY() * 6.0, facing.getModZ() * 6.0);

		target.setDirection(center.toVector().subtract(target.toVector()));
		player.teleport(target);
		sender.sendRichMessage("<green>✔ Đã dịch chuyển tới màn hình.</green>");
	}

	private void resend(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();

		for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
			if (instance.viewers().contains(player.getUniqueId()) && instance.display() != null) {
				instance.display().despawn(player);
			}
		}

		instance.viewers().clear();
		instance.placeholderStale();
		sender.sendRichMessage("<green>✔ Đã gửi lại màn hình cho người xem.</green>");
	}

	private void status(CommandSender sender) {
		TvConfig config = plugin.config();

		sender.sendRichMessage("<color:" + LunaPalette.NEUTRAL_300
			+ ">─── <bold>Luna TV · chẩn đoán</bold> ───</color>");
		line(sender, "MapEngine", dev.belikhun.luna.tv.display.DisplayService.available(), "đã có", "không tìm thấy");
		line(sender, "Chromium", ChromiumProcess.executableUsable(config), config.executable(),
			"không chạy được " + config.executable());

		String audioReason = audio.unavailableReason();
		line(sender, "Âm thanh", audioReason == null, "sẵn sàng", audioReason == null ? "" : audioReason);

		sender.sendRichMessage("<gray>Số màn hình: <white>" + screens.instances().size()
			+ "/" + config.maxScreens() + "</white> · fps <white>" + config.fps()
			+ "</white> · chất lượng <white>" + config.quality() + "</white></gray>");
	}

	private void line(CommandSender sender, String label, boolean ok, String good, String bad) {
		String mark = ok
			? "<color:" + LunaPalette.SUCCESS_500 + ">✔</color>"
			: "<color:" + LunaPalette.DANGER_500 + ">✖</color>";

		sender.sendRichMessage("<gray>" + mark + " " + label + ": <white>"
			+ MiniText.escape(ok ? good : bad) + "</white></gray>");
	}

	/**
	 * Turns tracing on or off without a restart.
	 *
	 * Tracing is what tells apart the three ways a screen can go quiet - browser
	 * not painting, frame not pushed, click not landing - so it has to be
	 * reachable while the thing is misbehaving.
	 */
	/** Sets a screen's frame rate; 0 follows the global render.fps. */
	private void fps(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		Integer value = args.length > 2 ? parseInt(args[2]) : null;

		if (value == null || value < 0 || value > 30) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv fps <tên> <0-30> (0 = theo config)"));

			return;
		}

		screens.fps(found.get(), value);
		sender.sendRichMessage("<green>✔ FPS của '" + MiniText.escape(found.get().name()) + "' giờ là "
			+ (value == 0
				? "theo config (" + screens.effectiveFps(found.get().screen()) + ")"
				: String.valueOf(value)) + ".</green>");
	}

	/** Sets one screen's dither mode or pattern; "config" follows the global setting. */
	private void dither(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
		// a pattern word both picks the pattern and switches the screen to
		// ORDERED, so comparing patterns is one command per look; "on" returns
		// the pattern to the config's
		String value = switch (mode) {
			case "on", "ordered" -> "ORDERED";
			case "bayer", "a1", "a2", "a3", "a4" -> "ORDERED";
			case "floyd", "floyd_steinberg" -> "FLOYD_STEINBERG";
			case "off", "direct" -> "DIRECT";
			case "config", "auto", "chung" -> "";
			default -> null;
		};
		String pattern = switch (mode) {
			case "bayer", "a1", "a2", "a3", "a4" -> mode;
			case "on", "ordered" -> "";
			default -> null;
		};

		if (value == null) {
			sender.sendRichMessage(CommandStrings.syntaxRaw(
				"/lunatv dither <tên> <on|off|floyd|bayer|a1|a2|a3|a4|config>"));
			sender.sendRichMessage("<gray>on = tán màu nhanh (gần như miễn phí CPU)"
				+ " · off = màu gần nhất (chữ và mảng phẳng sạch hơn)"
				+ " · floyd = mượt nhất, tốn CPU nhất</gray>");
			sender.sendRichMessage("<gray>bayer = lưới caro đều · a1/a2 = \"a dither\" dạng xor"
				+ " · a3/a4 = dạng cộng, mịn hơn; a2/a4 lệch riêng từng kênh màu</gray>");

			return;
		}

		screens.dither(found.get(), value, pattern);
		sender.sendRichMessage("<green>✔ Tán màu của '" + MiniText.escape(found.get().name())
			+ "': " + screens.converterLabel(found.get().screen()) + ".</green>");
	}

	/** Sets a screen's picture brightness as a percentage; 100 is untouched. */
	private void brightness(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		Integer value = args.length > 2 ? parseInt(args[2]) : null;

		if (value == null || value < 50 || value > 200) {
			sender.sendRichMessage(CommandStrings.syntaxRaw(
				"/lunatv brightness <tên> <50-200> (100 = nguyên bản)"));

			return;
		}

		screens.brightness(found.get(), value);
		sender.sendRichMessage("<green>✔ Độ sáng của '" + MiniText.escape(found.get().name())
			+ "' giờ là " + found.get().screen().brightness() + "%.</green>");
	}

	/** Sets a screen's bandwidth budget; 0 follows the global render.max-megabits. */
	private void bandwidth(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		Integer value = args.length > 2 ? parseInt(args[2]) : null;

		if (value == null || value < 0 || value > 1000) {
			sender.sendRichMessage(CommandStrings.syntaxRaw(
				"/lunatv bandwidth <tên> <0-1000> (Mbit/s, 0 = theo config)"));

			return;
		}

		screens.maxMegabits(found.get(), value);
		sender.sendRichMessage("<green>✔ Băng thông của '" + MiniText.escape(found.get().name()) + "' giờ là "
			+ (value == 0
				? "theo config (" + screens.effectiveMegabits(found.get().screen()) + " Mbit/s)"
				: value + " Mbit/s") + ".</green>");
	}

	/**
	 * Destroys map displays left behind by a failed teardown.
	 *
	 * Those frames are client-side only, so nothing in the world holds them and
	 * a reconnect also clears them; this removes them for everyone at once.
	 */
	private void cleanup(CommandSender sender) {
		int removed = screens.cleanupOrphans(panels::owns);

		sender.sendRichMessage(removed == 0
			? "<green>✔ Không có bản đồ mồ côi nào.</green>"
			: "<green>✔ Đã dọn " + removed + " màn hình mồ côi.</green>");
	}

	/** Powers a screen on or off; without an argument it toggles. */
	private void power(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();
		boolean on = args.length > 2
			? "on".equalsIgnoreCase(args[2])
			: !instance.powered();

		ScreenManager.Outcome outcome = screens.power(instance, on);

		if (!outcome.success()) {
			sender.sendRichMessage("<yellow>⚠ " + MiniText.escape(outcome.reason()) + "</yellow>");

			return;
		}

		sender.sendRichMessage(on
			? "<green>✔ Đã bật '" + MiniText.escape(instance.name()) + "'. Đang mở trình duyệt…</green>"
			: "<green>✔ Đã tắt '" + MiniText.escape(instance.name()) + "'. Màn hình chuyển đen.</green>");
	}

	/**
	 * Links the block the player is looking at as a power toggle, or clears
	 * the link with "off". A rising redstone edge there flips the screen.
	 */
	private void redstoneLink(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();

		if (args.length > 2 && "off".equalsIgnoreCase(args[2])) {
			screens.redstone(instance, null, null);
			redstone.refresh();
			sender.sendRichMessage("<green>✔ Đã gỡ liên kết redstone của '"
				+ MiniText.escape(instance.name()) + "'.</green>");

			return;
		}

		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Cần một người chơi nhìn vào nút/cần gạt để liên kết.</red>");

			return;
		}

		org.bukkit.block.Block target = player.getTargetBlockExact(6);

		if (target == null || target.getType().isAir()) {
			sender.sendRichMessage("<red>❌ Hãy nhìn thẳng vào nút, cần gạt hoặc bàn đạp rồi gõ lại lệnh.</red>");

			return;
		}

		screens.redstone(instance, target.getWorld().getName(),
			new org.bukkit.util.BlockVector(target.getX(), target.getY(), target.getZ()));
		redstone.refresh();
		sender.sendRichMessage("<green>✔ Nút tại <white>" + target.getX() + " " + target.getY() + " "
			+ target.getZ() + "</white> giờ bật/tắt màn hình '" + MiniText.escape(instance.name())
			+ "'.</green> <gray>(mỗi lần có tín hiệu redstone mới là một lần bật/tắt)</gray>");
	}

	/**
	 * Places a 2x2 touch control panel for a screen from the wand selection,
	 * or removes it with "off". The panel faces its creator, like a screen.
	 */
	private void panel(CommandSender sender, String[] args) {
		// removal is resolved by name, not by screen: a panel can outlive the
		// screen it controls, and that is exactly when it needs removing
		if (args.length > 2 && "off".equalsIgnoreCase(args[2])) {
			if (panels.remove(args[1])) {
				sender.sendRichMessage("<green>✔ Đã gỡ bảng điều khiển của '"
					+ MiniText.escape(args[1]) + "'.</green>");
			} else {
				sender.sendRichMessage("<yellow>⚠ Không có bảng điều khiển nào tên '"
					+ MiniText.escape(args[1]) + "'.</yellow>");
			}

			return;
		}

		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		String screenName = found.get().name();

		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Cần một người chơi chọn vị trí bằng đũa.</red>");

			return;
		}

		WandTool.Selection selection = wand.selection(player);

		if (selection == null || !selection.complete()) {
			sender.sendRichMessage("<red>❌ Chọn đúng vùng 2×2 khối bằng đũa trước "
				+ "(</red><white>/lunatv wand</white><red>).</red>");

			return;
		}

		BlockFace toward = WandTool.facingToward(selection, player);

		if (toward == null) {
			sender.sendRichMessage("<red>❌ Hai góc phải nằm trên một mặt phẳng.</red>");

			return;
		}

		String reason = panels.create(screenName, selection.world(),
			selection.cornerA(), selection.cornerB(), toward);

		if (reason != null) {
			sender.sendRichMessage("<red>❌ " + MiniText.escape(reason) + "</red>");

			return;
		}

		wand.clear(player);
		sender.sendRichMessage("<green>✔ Đã đặt bảng điều khiển 2×2 cho '"
			+ MiniText.escape(screenName) + "'. Chạm vào các nút trên đó để điều khiển.</green>");
	}

	/** Hands the sender the corner-selection wand. */
	private void giveWand(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Chỉ người chơi mới dùng được đũa chọn.</red>");

			return;
		}

		wand.give(player);
		player.sendRichMessage("<green>✔ Đã nhận Đũa Luna TV.</green> "
			+ "<gray>Chuột trái chọn góc một, chuột phải góc hai, rồi</gray> "
			+ "<white>/lunatv create <tên></white><gray>. Màn hình sẽ tự quay về phía bạn.</gray>");
	}

	/** Opens the settings GUI: the list, or one screen by name. */
	private void openGui(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ GUI chỉ mở được cho người chơi.</red>");

			return;
		}

		if (args.length < 2) {
			gui.openList(player);

			return;
		}

		Optional<ScreenInstance> found = require(sender, args);

		if (found.isPresent()) {
			gui.open(player, found.get());
		}
	}

	/** Sets a screen's capture divisor: 1 sharpest, 4 cheapest. */
	private void scale(CommandSender sender, String[] args) {
		Optional<ScreenInstance> found = require(sender, args);

		if (found.isEmpty()) {
			return;
		}

		Integer value = args.length > 2 ? parseInt(args[2]) : null;

		if (value == null || value < 1 || value > 4) {
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv scale <tên> <1-4>"));

			return;
		}

		screens.scale(found.get(), value);
		sender.sendRichMessage("<green>✔ Độ phân giải của '" + MiniText.escape(found.get().name())
			+ "' giờ là 1/" + value + ".</green>");
	}

	private void debug(CommandSender sender, String[] args) {
		boolean on = args.length > 1 ? "on".equalsIgnoreCase(args[1]) : !TvDebug.enabled();

		TvDebug.enabled(on);
		sender.sendRichMessage("<green>✔ Trace " + (on ? "đã bật" : "đã tắt")
			+ ". Xem trong log của máy chủ.</green>");
	}

	private void reload(CommandSender sender) {
		plugin.reloadTvConfig();
		sender.sendRichMessage("<green>✔ Đã nạp lại config.yml. "
			+ "Thay đổi trong mục browser chỉ áp dụng khi màn hình mở lại.</green>");
	}

	private void panel(CommandSender sender, ScreenInstance instance) {
		ControlPanel.send(sender, instance, plugin.config(), audio.unavailableReason() == null);
	}

	private Optional<ScreenInstance> require(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendRichMessage("<red>❌ Thiếu tên màn hình.</red>");
			sender.sendRichMessage(CommandStrings.syntaxRaw("/lunatv " + args[0] + " <tên>"));

			return Optional.empty();
		}

		Optional<ScreenInstance> found = screens.find(args[1]);

		if (found.isEmpty()) {
			sender.sendRichMessage("<red>❌ Không có màn hình tên '"
				+ MiniText.escape(args[1]) + "'.</red>");
		}

		return found;
	}

	private static String stateWord(ScreenInstance instance) {
		return instance.state().label();
	}

	/**
	 * Reads the value of a named option out of the argument list.
	 *
	 * @param args the raw arguments
	 * @param option the option name, including its dashes
	 * @return the value following it, or null when absent or unvalued
	 */
	private static String optionValue(String[] args, String option) {
		for (int index = 0; index < args.length - 1; index++) {
			if (args[index].equalsIgnoreCase(option)) {
				return args[index + 1];
			}
		}

		return null;
	}

	/**
	 * The arguments with a named option and its value removed, so the rest can be
	 * read positionally.
	 *
	 * @param args the raw arguments
	 * @param option the option name, including its dashes
	 * @return a new array without that option
	 */
	private static String[] withoutOption(String[] args, String option) {
		List<String> kept = new ArrayList<>();

		for (int index = 0; index < args.length; index++) {
			if (args[index].equalsIgnoreCase(option)) {
				index++;
				continue;
			}

			kept.add(args[index]);
		}

		return kept.toArray(new String[0]);
	}

	private static Integer parseInt(String raw) {
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static BlockFace parseFacing(String raw) {
		try {
			BlockFace facing = BlockFace.valueOf(raw.toUpperCase(Locale.ROOT));

			return ScreenManager.FACINGS.contains(facing.name()) ? facing : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	/**
	 * Rejects anything that is not a web page.
	 *
	 * A bare host is completed to https, and every other scheme is refused: the
	 * browser runs on the server, so file: and chrome: would be reading the
	 * server's own disk and settings, not the clicker's.
	 */
	private static String normalizeUrl(String raw) {
		String trimmed = raw.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);

		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return trimmed;
		}

		if (lower.contains("://")) {
			return null;
		}

		return "https://" + trimmed;
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (!source.getSender().hasPermission(PERMISSION)) {
			return List.of();
		}

		if (args.length == 0) {
			return SUBCOMMANDS;
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(SUBCOMMANDS, args[0]);
		}

		String verb = args[0].toLowerCase(Locale.ROOT);

		if (args.length == 2 && !verb.equals("create")) {
			return CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1]);
		}

		return switch (verb) {
			case "create" -> createSuggestions(source, args);
			case "debug" -> args.length == 2
				? CommandCompletions.filterPrefix(TOGGLES, args[1])
				: List.of();
			case "panel" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("off"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "redstone" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("off"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "lock", "audio", "power", "stereo", "scroll" -> args.length == 3
				? CommandCompletions.filterPrefix(TOGGLES, args[2])
				: List.of();
			case "clear" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("sau"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "key" -> args.length == 3
				? CommandCompletions.filterPrefix(KEYS, args[2])
				: List.of();
			case "dither" -> args.length == 3
				? CommandCompletions.filterPrefix(
					List.of("on", "off", "floyd", "bayer", "a1", "a2", "a3", "a4", "config"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "brightness" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("100", "120", "140", "160", "180"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "bandwidth" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("0", "20", "36", "50", "100", "200"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "fps" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("0", "5", "10", "15", "20", "30"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "scale" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("1", "2", "3", "4"), args[2])
				: args.length == 2
					? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
					: List.of();
			case "gui" -> args.length == 2
				? CommandCompletions.filterPrefix(new ArrayList<>(screens.names()), args[1])
				: List.of();
			case "volume" -> args.length == 3
				? CommandCompletions.filterPrefix(List.of("0", "25", "50", "75", "100"), args[2])
				: List.of();
			case "url" -> args.length == 3 ? presetUrls(args[2]) : List.of();
			default -> List.of();
		};
	}

	private List<String> presetUrls(String prefix) {
		List<String> urls = new ArrayList<>();

		for (TvConfig.Preset preset : plugin.config().presets()) {
			urls.add(preset.url());
		}

		return CommandCompletions.filterPrefix(urls, prefix);
	}

	/**
	 * Completions for create: the block the player is looking at, so a wall can
	 * be measured by standing in front of it rather than by reading coordinates.
	 */
	private List<String> createSuggestions(CommandSourceStack source, String[] args) {
		if (!(source.getSender() instanceof Player player)) {
			return List.of();
		}

		if (args.length == 9) {
			return CommandCompletions.filterPrefix(new ArrayList<>(ScreenManager.FACINGS), args[8]);
		}

		if (args.length < 3 || args.length > 8) {
			return List.of();
		}

		var target = player.getTargetBlockExact(24);

		if (target == null) {
			return List.of();
		}

		// argument 3 is x1, 4 is y1, 5 is z1, 6 is x2, 7 is y2, 8 is z2
		int slot = (args.length - 3) % 3;
		int value = switch (slot) {
			case 0 -> target.getX();
			case 1 -> target.getY();
			default -> target.getZ();
		};

		return List.of(String.valueOf(value));
	}
}
