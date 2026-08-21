package dev.belikhun.luna.tv.panel;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.event.MapClickEvent;
import de.pianoman911.mapengine.api.pipeline.IPipelineContext;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;
import dev.belikhun.luna.tv.screen.TvScreen;

/**
 * The physical remote: a small map wall whose pixels are buttons.
 *
 * One panel per screen, drawn by {@link PanelRenderer} and clicked through
 * MapEngine's own click event. Redrawn once a second while anyone is near,
 * because it shows live status; MapEngine's per-player delta keeps an
 * unchanged redraw off the wire.
 *
 * Flushes run on the main thread: the panel is 4 maps, the conversion is
 * microseconds, and it spares the whole async-reuse problem the big screens
 * solve with buffer rotation.
 */
public final class TouchPanelService implements Listener {

	/** One placed panel and its runtime display objects. */
	public static final class Panel {

		private final String screen;
		private final String world;
		private final BlockVector cornerA;
		private final BlockVector cornerB;
		private final BlockFace facing;

		private IMapDisplay display;
		private IDrawingSpace drawing;
		private PanelRenderer renderer;
		private final java.util.Set<UUID> viewers = ConcurrentHashMap.newKeySet();

		private volatile String pressedId;
		private volatile long pressedAt;
		private long lastSync;
		private long lastDraw;

		private Panel(String screen, String world, BlockVector cornerA, BlockVector cornerB, BlockFace facing) {
			this.screen = screen;
			this.world = world;
			this.cornerA = cornerA;
			this.cornerB = cornerB;
			this.facing = facing;
		}

		public String screen() {
			return screen;
		}
	}

	/** Pixels one press of the scroll button moves the page. */
	private static final int SCROLL_STEP = 240;

	/** How long a pressed button stays lit, in milliseconds. */
	private static final long PRESS_FADE_MS = 260L;
	/** How often the panel repaints purely to refresh its status. */
	private static final long STATUS_INTERVAL_MS = 1_000L;

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final ScreenManager screens;
	private final Map<String, Panel> panels = new ConcurrentHashMap<>();

	private volatile TvConfig config;
	private BukkitTask task;

	public TouchPanelService(JavaPlugin plugin, LunaLogger logger, ScreenManager screens, TvConfig config) {
		this.plugin = plugin;
		this.logger = logger;
		this.screens = screens;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/** Loads persisted panels and starts the refresh loop. */
	public void start() {
		load();
		// every tick, because a press animation needs finer grain than a second;
		// the body itself only repaints when there is a reason to
		task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
	}

	public void shutdown() {
		if (task != null) {
			task.cancel();
			task = null;
		}

		for (Panel panel : panels.values()) {
			detach(panel);
		}

		panels.clear();
	}

	/**
	 * Creates a panel for a screen from two selected corners.
	 *
	 * @param screen the screen it controls
	 * @param world the panel's world
	 * @param cornerA one corner
	 * @param cornerB the other
	 * @param facing which way it faces
	 * @return null on success, else a human reason
	 */
	public String create(String screen, World world, BlockVector cornerA, BlockVector cornerB, BlockFace facing) {
		if (panels.containsKey(key(screen))) {
			return "Màn hình '" + screen + "' đã có bảng điều khiển; gỡ bằng /lunatv panel " + screen + " off.";
		}

		Panel panel = new Panel(screen, world.getName(), cornerA, cornerB, facing);

		if (!attach(panel)) {
			return "Không tạo được bảng điều khiển (thế giới chưa nạp?).";
		}

		if (panel.display.width() != 2 || panel.display.height() != 2) {
			detach(panel);

			return "Bảng điều khiển phải đúng 2×2 khối (đang là "
				+ panel.display.width() + "×" + panel.display.height() + ").";
		}

		panels.put(key(screen), panel);
		save();

		return null;
	}

	/**
	 * Removes a screen's panel.
	 *
	 * @param screen the screen whose panel goes
	 * @return true when one existed
	 */
	public boolean remove(String screen) {
		Panel panel = panels.remove(key(screen));

		if (panel == null) {
			return false;
		}

		detach(panel);
		save();

		return true;
	}

	private boolean attach(Panel panel) {
		World world = Bukkit.getWorld(panel.world);

		if (world == null) {
			return false;
		}

		MapEngineApi api = MapEngineApi.instance();

		panel.display = api.displayProvider().createBasic(panel.cornerA, panel.cornerB, panel.facing);
		panel.display.interactDistance(config.interactDistance());

		IPipelineContext ctx = api.pipeline().createCtx(panel.display);
		FullSpacedColorBuffer buffer = new FullSpacedColorBuffer(
			panel.display.pixelWidth(), panel.display.pixelHeight());

		panel.drawing = api.pipeline().drawingSpace(ctx, buffer);

		ctx.converter(de.pianoman911.mapengine.api.util.Converter.DIRECT);
		ctx.buffering(true);
		ctx.bundling(config.bundling());
		panel.renderer = new PanelRenderer();

		return true;
	}

	private void detach(Panel panel) {
		for (UUID viewer : panel.viewers) {
			Player player = Bukkit.getPlayer(viewer);

			if (player != null && panel.display != null) {
				panel.display.despawn(player);
			}
		}

		panel.viewers.clear();

		if (panel.display != null) {
			panel.display.destroy();
			panel.display = null;
			panel.drawing = null;
		}
	}

	private void tick() {
		long now = System.currentTimeMillis();

		for (Panel panel : panels.values()) {
			if (panel.display == null) {
				attach(panel);

				continue;
			}

			World world = Bukkit.getWorld(panel.world);

			if (world == null) {
				continue;
			}

			if (now - panel.lastSync >= STATUS_INTERVAL_MS) {
				panel.lastSync = now;
				syncViewers(panel, world);
			}

			if (panel.viewers.isEmpty()) {
				continue;
			}

			boolean fading = panel.pressedId != null && now - panel.pressedAt < PRESS_FADE_MS;

			if (!fading && panel.pressedId != null) {
				// one last frame to clear the highlight
				panel.pressedId = null;
				panel.lastDraw = now;
				redraw(panel);

				continue;
			}

			if (fading || now - panel.lastDraw >= STATUS_INTERVAL_MS) {
				panel.lastDraw = now;
				redraw(panel);
			}
		}
	}

	private void syncViewers(Panel panel, World world) {
		double range = config.spawnDistance();
		Location center = new Location(world,
			(panel.cornerA.getX() + panel.cornerB.getX()) / 2.0 + 0.5,
			(panel.cornerA.getY() + panel.cornerB.getY()) / 2.0 + 0.5,
			(panel.cornerA.getZ() + panel.cornerB.getZ()) / 2.0 + 0.5);

		for (Player player : world.getPlayers()) {
			boolean near = player.getLocation().distance(center) <= range;
			boolean viewing = panel.viewers.contains(player.getUniqueId());

			if (near && !viewing) {
				panel.display.spawn(player);
				panel.drawing.ctx().removeReceiver(player);
				panel.drawing.ctx().addReceiver(player);
				panel.viewers.add(player.getUniqueId());
			} else if (!near && viewing) {
				panel.drawing.ctx().removeReceiver(player);
				panel.display.despawn(player);
				panel.viewers.remove(player.getUniqueId());
			}
		}
	}

	private void redraw(Panel panel) {
		ScreenInstance instance = screens.find(panel.screen).orElse(null);

		if (instance == null || panel.drawing == null) {
			return;
		}

		TvScreen screen = instance.screen();
		PanelRenderer.State state = new PanelRenderer.State(
			screen.name(),
			instance.powered(),
			instance.state().label(),
			instance.state() == ScreenState.RUNNING,
			instance.viewers().size(),
			screen.volume(),
			screen.audio(),
			screen.url(),
			screens.effectiveFps(screen),
			screen.scale());

		float press = 0f;

		if (panel.pressedId != null) {
			long elapsed = System.currentTimeMillis() - panel.pressedAt;

			press = Math.max(0f, 1f - (float) elapsed / PRESS_FADE_MS);
		}

		int[] pixels = panel.renderer.render(state, panel.pressedId, press);

		panel.drawing.pixels(pixels, 0, 0, 256, 256);
		panel.drawing.flush();
	}

	@EventHandler
	public void onMapClick(MapClickEvent event) {
		for (Panel panel : panels.values()) {
			if (panel.display == null || !panel.display.equals(event.display())) {
				continue;
			}

			press(panel, event.player(), event.x(), event.y());

			return;
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		for (Panel panel : panels.values()) {
			if (panel.viewers.remove(event.getPlayer().getUniqueId()) && panel.drawing != null) {
				panel.drawing.ctx().removeReceiver(event.getPlayer());
			}
		}
	}

	private void press(Panel panel, Player player, int x, int y) {
		ScreenInstance instance = screens.find(panel.screen).orElse(null);

		if (instance == null) {
			return;
		}

		for (PanelRenderer.Widget widget : panel.renderer.widgets()) {
			if (!widget.hit(x, y)) {
				continue;
			}

			panel.pressedId = widget.id();
			panel.pressedAt = System.currentTimeMillis();
			act(panel, instance, player, widget, x);

			return;
		}
	}

	private void act(Panel panel, ScreenInstance instance, Player player, PanelRenderer.Widget widget, int x) {
		TvScreen screen = instance.screen();
		CdpBrowser browser = instance.browser();

		switch (widget.id()) {
			case "power" -> screens.power(instance, !instance.powered());
			case "back" -> {
				if (browser != null) {
					browser.back();
				}
			}
			case "forward" -> {
				if (browser != null) {
					browser.forward();
				}
			}
			case "reload" -> {
				if (browser != null) {
					browser.reload();
				}
			}
			case "home" -> screens.navigate(instance, config.homepage());
			case "play" -> {
				if (browser != null) {
					browser.evaluate("(()=>{const v=document.querySelector('video');"
						+ "if(v){v.paused?v.play():v.pause();}})()");
				}
			}
			case "seekback" -> {
				if (browser != null) {
					browser.evaluate("(()=>{const v=document.querySelector('video');"
						+ "if(v){v.currentTime=Math.max(0,v.currentTime-10);}})()");
				}
			}
			case "seekfwd" -> {
				if (browser != null) {
					browser.evaluate("(()=>{const v=document.querySelector('video');"
						+ "if(v){v.currentTime+=10;}})()");
				}
			}
			case "voldown" -> screens.volume(instance, screen.volume() - 10);
			case "volup" -> screens.volume(instance, screen.volume() + 10);
			case "volume" -> {
				// the slider: the press position IS the value
				int value = (x - widget.x()) * 100 / Math.max(1, widget.width());

				screens.volume(instance, value);
			}
			case "mute" -> screens.audio(instance, !screen.audio());
			case "up", "down", "left", "right", "tab", "enter", "esc", "backspace" -> {
				if (browser != null) {
					browser.key(widget.id());
				}
			}
			case "scrollup", "scrolldown" -> {
				if (browser != null) {
					// aimed at the middle of the page, which is what a viewer
					// standing in front of the wall means by "scroll"
					browser.scroll(browser.width() / 2, browser.height() / 2,
						"scrollup".equals(widget.id()) ? -SCROLL_STEP : SCROLL_STEP);
				}
			}
			default -> {
				return;
			}
		}

		// feedback is immediate: the panel repaints in the same tick as the press
		redraw(panel);
		player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
	}

	private File file() {
		return new File(plugin.getDataFolder(), "panels.yml");
	}

	private void load() {
		File file = file();

		if (!file.exists()) {
			return;
		}

		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

		for (String name : yaml.getKeys(false)) {
			ConfigurationSection section = yaml.getConfigurationSection(name);

			if (section == null) {
				continue;
			}

			try {
				Panel panel = new Panel(
					name,
					section.getString("world", "world"),
					new BlockVector(section.getInt("a.x"), section.getInt("a.y"), section.getInt("a.z")),
					new BlockVector(section.getInt("b.x"), section.getInt("b.y"), section.getInt("b.z")),
					BlockFace.valueOf(section.getString("facing", "NORTH").toUpperCase(Locale.ROOT)));

				panels.put(key(name), panel);
				attach(panel);
			} catch (Exception exception) {
				logger.warn("Bỏ qua bảng điều khiển '" + name + "': " + exception.getMessage());
			}
		}

		if (!panels.isEmpty()) {
			logger.info("Đã nạp " + panels.size() + " bảng điều khiển từ panels.yml.");
		}
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();

		for (Panel panel : panels.values()) {
			String base = panel.screen;

			yaml.set(base + ".world", panel.world);
			yaml.set(base + ".a.x", panel.cornerA.getBlockX());
			yaml.set(base + ".a.y", panel.cornerA.getBlockY());
			yaml.set(base + ".a.z", panel.cornerA.getBlockZ());
			yaml.set(base + ".b.x", panel.cornerB.getBlockX());
			yaml.set(base + ".b.y", panel.cornerB.getBlockY());
			yaml.set(base + ".b.z", panel.cornerB.getBlockZ());
			yaml.set(base + ".facing", panel.facing.name());
		}

		try {
			yaml.save(file());
		} catch (Exception exception) {
			logger.error("Không lưu được panels.yml.", exception);
		}
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
