package dev.belikhun.luna.tv;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.help.HelpArgument;
import dev.belikhun.luna.core.api.help.HelpCategory;
import dev.belikhun.luna.core.api.help.HelpEntry;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import dev.belikhun.luna.tv.audio.AudioService;
import dev.belikhun.luna.tv.browser.ChromiumProcess;
import dev.belikhun.luna.tv.command.LunaTvCommand;
import dev.belikhun.luna.tv.display.DisplayService;
import dev.belikhun.luna.tv.display.RenderPump;
import dev.belikhun.luna.tv.display.ViewerTracker;
import dev.belikhun.luna.core.api.gui.AnvilInputManager;
import dev.belikhun.luna.core.api.gui.GuiManager;

import dev.belikhun.luna.tv.gui.ScreenSettingsGui;
import dev.belikhun.luna.tv.input.MapClickListener;
import dev.belikhun.luna.tv.input.RedstoneListener;
import dev.belikhun.luna.tv.input.WandTool;
import dev.belikhun.luna.tv.panel.TouchPanelService;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;
import dev.belikhun.luna.tv.screen.TvScreenStore;

/**
 * Luna TV: browser screens on map walls, with their sound in voice chat.
 *
 * Nothing here blocks: Chromium is started off the main thread and screens show
 * a notice until their browser answers, because a server that pauses on enable
 * while four browsers boot is worse than a wall that says "đang mở" for two
 * seconds.
 */
public final class LunaTvPlugin extends JavaPlugin {

	private LunaLogger logger;
	private ConfigStore configStore;
	private volatile TvConfig config;

	private DisplayService displays;
	private AudioService audio;
	private ScreenManager screens;
	private RenderPump render;
	private ViewerTracker viewers;
	private MapClickListener clicks;
	private WandTool wand;
	private RedstoneListener redstone;
	private TouchPanelService panels;
	private ScreenSettingsGui gui;

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore",
			"LunaCore chưa sẵn sàng. LunaTv sẽ tắt.")) {
			return;
		}

		saveDefaultConfig();
		configStore = ConfigStore.of(this, "config.yml");
		config = TvConfig.from(configStore);
		logger = PaperPluginBootstrap.initLogger(this, "TV").withDebug(config.debug());
		TvDebug.init(logger, config.debug());

		// every screencast frame builds an image input stream; the default cache
		// backs those with temp files on disk
		javax.imageio.ImageIO.setUseCache(false);

		if (!DisplayService.available()) {
			logger.error("Không tìm thấy MapEngine. LunaTv sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);

			return;
		}

		if (!ChromiumProcess.executableUsable(config)) {
			logger.warn("Không chạy được '" + config.executable()
				+ "'. Màn hình sẽ báo lỗi cho tới khi sửa browser.executable trong config.yml.");
		}

		displays = new DisplayService(logger, config);
		audio = new AudioService(this, logger, config);
		screens = new ScreenManager(this, logger, config, displays, audio,
			new TvScreenStore(this, logger));
		render = new RenderPump(logger, config, () -> screens.instances());
		viewers = new ViewerTracker(this, screens, displays, audio);

		audio.enable(this::onVoiceChatReady);
		screens.loadAll();
		render.start();
		viewers.start();

		clicks = new MapClickListener(this, screens, config);
		wand = new WandTool(this);
		redstone = new RedstoneListener(screens, logger.scope("Redstone"));
		panels = new TouchPanelService(this, logger.scope("Panel"), screens, config);

		GuiManager guis = new GuiManager();
		AnvilInputManager anvil = new AnvilInputManager(this);

		gui = new ScreenSettingsGui(this, screens, guis, anvil);

		getServer().getPluginManager().registerEvents(viewers, this);
		getServer().getPluginManager().registerEvents(clicks, this);
		getServer().getPluginManager().registerEvents(wand, this);
		getServer().getPluginManager().registerEvents(redstone, this);
		getServer().getPluginManager().registerEvents(panels, this);
		getServer().getPluginManager().registerEvents(guis, this);
		getServer().getPluginManager().registerEvents(anvil, this);

		// after every listener exists: the index reads what loadAll stored, and
		// the panels attach their displays
		redstone.refresh();
		panels.start();
		wand.start();

		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			LunaTvCommand command = new LunaTvCommand(this, screens, audio, wand, gui, redstone, panels);

			commands.registrar().register("lunatv", command);
			commands.registrar().register("tv", command);
		});

		registerHelp();
		logger.success("LunaTv đã sẵn sàng.");
	}

	@Override
	public void onDisable() {
		if (panels != null) {
			panels.shutdown();
		}

		if (wand != null) {
			wand.stop();
		}

		if (viewers != null) {
			viewers.stop();
		}

		if (render != null) {
			render.stop();
		}

		if (audio != null) {
			audio.disable();
		}

		// last: it closes the browsers and saves screens.yml synchronously
		if (screens != null) {
			screens.shutdown();
		}
	}

	/**
	 * Voice chat came up after the screens did, so screens that want sound and
	 * are already running can start streaming now.
	 */
	private void onVoiceChatReady() {
		for (ScreenInstance instance : screens.instances()) {
			if (instance.screen().audio() && instance.state() == ScreenState.RUNNING) {
				screens.audio(instance, true);
			}
		}
	}

	/** The live configuration snapshot. */
	public TvConfig config() {
		return config;
	}

	/**
	 * Re-reads config.yml and pushes the new snapshot into every subsystem.
	 *
	 * Browser switches are not re-applied: they are process arguments, and a
	 * running Chromium cannot be told about them. The command says so.
	 */
	public void reloadTvConfig() {
		configStore.reload();
		config = TvConfig.from(configStore);

		displays.config(config);
		audio.config(config);
		screens.config(config);
		render.config(config);
		clicks.config(config);
		TvDebug.enabled(config.debug());
		panels.config(config);
	}

	private void registerHelp() {
		try {
			LunaCore.services().helpRegistry().registerCategory(new HelpCategory(
				"lunatv", "LunaTv", "Luna TV", Material.PAINTING,
				"Màn hình trình duyệt web trên tường bản đồ"));

			LunaCore.services().helpRegistry().register(new HelpEntry(
				"LunaTv", "lunatv", Material.PAINTING, "/lunatv control",
				"Mở bảng điều khiển của một màn hình.", "lunatv.control",
				List.of("/lunatv control tivi"),
				List.of(new HelpArgument("tên", true, "Tên màn hình.", List.of()))));

			LunaCore.services().helpRegistry().register(new HelpEntry(
				"LunaTv", "lunatv", Material.MAP, "/lunatv create",
				"Tạo màn hình mới từ hai góc của tường.", "lunatv.control",
				List.of("/lunatv create tivi 10 70 20 13 73 20 north"),
				List.of(
					new HelpArgument("tên", true, "Tên màn hình.", List.of()),
					new HelpArgument("toạ độ", true, "Hai góc đối diện của tường.", List.of()),
					new HelpArgument("hướng", true, "Mặt tường hướng ra.", ScreenManager.FACINGS))));

			LunaCore.services().helpRegistry().register(new HelpEntry(
				"LunaTv", "lunatv", Material.JUKEBOX, "/lunatv audio",
				"Bật hoặc tắt tiếng của màn hình trong voice chat.", "lunatv.control",
				List.of("/lunatv audio tivi on"),
				List.of(new HelpArgument("tên", true, "Tên màn hình.", List.of()))));
		} catch (Throwable throwable) {
			logger.warn("Không đăng ký được mục trợ giúp: " + throwable);
		}
	}
}
