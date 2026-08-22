package dev.belikhun.luna.tv.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.audio.AudioService;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.display.DisplayService;

/**
 * The screens, and everything that has to happen in step across them.
 *
 * Creating a screen is four things that can each fail on their own: a map
 * display, a browser process, a sink and an audio stream. They are ordered so
 * that a failure late on still leaves something useful on the wall, which is
 * why the display comes up first and the browser attaches to it afterwards.
 *
 * Every method here runs on the main thread unless its documentation says
 * otherwise; the render thread only reads {@link #instances()}.
 */
public final class ScreenManager {

	/** Facings a wall can be built against. */
	public static final List<String> FACINGS = List.of("NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN");

	/**
	 * Above this many maps, the render cost is worth a warning.
	 *
	 * There is deliberately no upper limit on screen size: the real ceiling is
	 * memory and dither time, both of which scale smoothly and are visible in
	 * `/lunatv info`, so a number picked here would only be in the way.
	 */
	private static final int LARGE_MAPS = 64;

	/**
	 * How far in from each edge a stereo screen's speakers sit, as a fraction
	 * of its width.
	 */
	private static final double SPEAKER_INSET = 0.15;

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final DisplayService displays;
	private final AudioService audio;
	private final TvScreenStore store;
	private final Map<String, ScreenInstance> instances = new LinkedHashMap<>();

	private volatile TvConfig config;

	public ScreenManager(
		JavaPlugin plugin,
		LunaLogger logger,
		TvConfig config,
		DisplayService displays,
		AudioService audio,
		TvScreenStore store
	) {
		this.plugin = plugin;
		this.logger = logger;
		this.config = config;
		this.displays = displays;
		this.audio = audio;
		this.store = store;
	}

	public void config(TvConfig config) {
		this.config = config;

		// live browsers keep their process, but the display rate is just a number
		// they compare against, so a reload can retune them in place
		for (ScreenInstance instance : instances.values()) {
			CdpBrowser browser = instance.browser();

			if (browser != null) {
				browser.displayRate(effectiveFps(instance.screen()));
			}

			displays.applyRenderSettings(instance);
		}
	}

	/** Live view of the screens, read by the render thread. */
	public Collection<ScreenInstance> instances() {
		return new ArrayList<>(instances.values());
	}

	/**
	 * Finds a screen by name, case-insensitively.
	 *
	 * @param name the screen's name
	 * @return the instance, or empty
	 */
	public Optional<ScreenInstance> find(String name) {
		for (ScreenInstance instance : instances.values()) {
			if (instance.name().equalsIgnoreCase(name)) {
				return Optional.of(instance);
			}
		}

		return Optional.empty();
	}

	/**
	 * Finds the screen a MapEngine display belongs to.
	 *
	 * @param display the display a click arrived on
	 * @return the instance, or empty when the display is not ours
	 */
	public Optional<ScreenInstance> byDisplay(IMapDisplay display) {
		for (ScreenInstance instance : instances.values()) {
			if (instance.display() == display) {
				return Optional.of(instance);
			}
		}

		return Optional.empty();
	}

	/** Names of every screen, for tab completion. */
	public List<String> names() {
		return instances.values().stream().map(ScreenInstance::name).toList();
	}

	/** Brings every remembered screen up. Called once, after enable. */
	public void loadAll() {
		for (TvScreen screen : store.load()) {
			ScreenInstance instance = new ScreenInstance(screen);

			instances.put(key(screen.name()), instance);
			bring(instance);
		}

		if (!instances.isEmpty()) {
			logger.info("Đã nạp " + instances.size() + " màn hình từ screens.yml.");
		}
	}

	/**
	 * Creates a screen and starts it.
	 *
	 * @param name the screen's name, unique
	 * @param world the world holding it
	 * @param cornerA one corner block of the wall
	 * @param cornerB the opposite corner block
	 * @param facing which way the wall faces
	 * @param url the first page to show
	 * @param creator who made it, for the record
	 * @return the outcome, with a reason when it failed
	 */
	public Outcome create(
		String name,
		World world,
		BlockVector cornerA,
		BlockVector cornerB,
		BlockFace facing,
		String url,
		String creator
	) {
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
			return Outcome.failed("Tên chỉ được dùng chữ, số, gạch ngang và gạch dưới (tối đa 32).");
		}

		if (find(name).isPresent()) {
			return Outcome.failed("Đã có màn hình tên '" + name + "'.");
		}

		if (instances.size() >= config.maxScreens()) {
			return Outcome.failed("Đã đạt giới hạn " + config.maxScreens() + " màn hình (screens.max).");
		}

		if (!FACINGS.contains(facing.name())) {
			return Outcome.failed("Hướng không hợp lệ: " + facing.name());
		}

		TvScreen screen = new TvScreen(name, world.getName(), cornerA, cornerB, facing,
			url, 100, false, false, config.captureScale(), 0, 0, config.brightness(), "", "", true, true,
			creator, System.currentTimeMillis());

		int maps = screen.mapsWide() * screen.mapsHigh();

		// past this size the dither and the packet volume per frame are what limit
		// the wall, not the browser; the operator should know before it feels slow
		if (maps > LARGE_MAPS) {
			long pixels = (long) screen.pixelWidth() * screen.pixelHeight();
			long poolMegabytes = pixels * 4 * 3 / (1024 * 1024);

			logger.warn("Màn hình '" + name + "' rất lớn: " + screen.mapsWide() + "×" + screen.mapsHigh()
				+ " = " + maps + " bản đồ (" + screen.pixelWidth() + "×" + screen.pixelHeight()
				+ "px, ~" + poolMegabytes + "MB bộ đệm khung hình). Nếu thấy chậm: giảm render.fps, "
				+ "hoặc đổi render.converter sang ORDERED/DIRECT (rẻ hơn FLOYD_STEINBERG nhiều).");
		}

		ScreenInstance instance = new ScreenInstance(screen);

		// a screen someone just built starts working at once; only a server boot
		// leaves screens off
		instance.powered(true);
		instances.put(key(name), instance);

		if (!bring(instance)) {
			instances.remove(key(name));

			return Outcome.failed("Không tạo được màn hình trên thế giới '" + world.getName() + "'.");
		}

		persist();

		return Outcome.ok();
	}

	/**
	 * Starts a screen's display, browser and audio.
	 *
	 * @param instance the screen to bring up
	 * @return true when at least the display came up
	 */
	private boolean bring(ScreenInstance instance) {
		if (!displays.attach(instance)) {
			instance.state(ScreenState.SUSPENDED);

			return false;
		}

		// an unpowered screen gets its maps and a black wall, nothing else
		if (!instance.powered()) {
			instance.state(ScreenState.OFF);
			instance.placeholderStale();

			return true;
		}

		launchBrowser(instance);

		return true;
	}

	/**
	 * Powers a screen on or off.
	 *
	 * On starts the browser and, when the screen's audio preference is on,
	 * the voice-chat stream with it. Off closes the browser outright and the
	 * wall goes black; the audio preference is kept for the next power-on.
	 *
	 * @param instance the screen
	 * @param on the desired state
	 * @return the outcome, with a reason when nothing changed
	 */
	public Outcome power(ScreenInstance instance, boolean on) {
		if (on == instance.powered()) {
			return Outcome.failed("Màn hình '" + instance.name() + "' đã "
				+ (on ? "bật" : "tắt") + " sẵn rồi.");
		}

		instance.powered(on);

		if (on) {
			if (instance.state() == ScreenState.SUSPENDED) {
				return Outcome.failed("Thế giới của màn hình chưa nạp; sẽ tự mở khi nạp xong.");
			}

			instance.relaunchSettled();
			launchBrowser(instance);

			return Outcome.ok();
		}

		audio.stop(instance.name());
		closeBrowser(instance);
		instance.state(ScreenState.OFF);
		instance.placeholderStale();
		instance.requestRedraw();

		return Outcome.ok();
	}

	/**
	 * Launches (or relaunches) a screen's browser.
	 *
	 * Chromium takes a second or two to open its debug port, so the launch runs
	 * off the main thread and the result is handed back to it.
	 *
	 * @param instance the screen
	 */
	public void launchBrowser(ScreenInstance instance) {
		TvScreen screen = instance.screen();

		// one launch at a time: the previous one may still be waiting for Chromium
		// to open its port, and a second would leave an orphan browser behind
		if (!instance.claimLaunch()) {
			logger.info("Đang mở trình duyệt cho '" + instance.name() + "' rồi, bỏ qua yêu cầu trùng.");

			return;
		}

		instance.state(ScreenState.STARTING);
		instance.placeholderStale();

		String url = screen.url() == null || screen.url().isBlank() ? config.homepage() : screen.url();
		String sink = audio.prepareSink(screen.name());
		Path profile = profileDir(screen.name());
		String dither = displays.browserPattern(instance);

		plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				CdpBrowser browser = CdpBrowser.start(
					logger.scope(screen.name()),
					config,
					profile,
					sink,
					screen.pixelWidth(),
					screen.pixelHeight(),
					screen.scale(),
					screen.brightness(),
					dither,
					url,
					throwable -> plugin.getServer().getScheduler()
						.runTask(plugin, () -> onBrowserDied(instance, throwable)));

				plugin.getServer().getScheduler().runTask(plugin, () -> onBrowserReady(instance, browser, sink));
			} catch (Throwable throwable) {
				plugin.getServer().getScheduler()
					.runTask(plugin, () -> onLaunchFailed(instance, throwable));
			}
		});
	}

	private void onBrowserReady(ScreenInstance instance, CdpBrowser browser, String sink) {
		instance.releaseLaunch();

		// an instance already holding a live browser keeps it; the newcomer is the
		// loser of a race and would otherwise linger unreferenced
		if (instance.browser() != null) {
			logger.warn("Màn hình '" + instance.name() + "' đã có trình duyệt, đóng bản vừa mở.");
			browser.close();

			return;
		}

		// powered off while Chromium was still starting: the browser is not wanted
		if (!instance.powered()) {
			browser.close();
			instance.state(ScreenState.OFF);
			instance.placeholderStale();

			return;
		}

		// the screen may have been removed while Chromium was starting
		if (!instances.containsValue(instance)) {
			browser.close();

			return;
		}

		instance.browser(browser);
		instance.state(ScreenState.RUNNING);
		instance.failure(null);
		instance.relaunchSettled();

		if (instance.screen().audio()) {
			startAudio(instance, sink);
		}

		browser.displayRate(effectiveFps(instance.screen()));
		logger.info("Màn hình '" + instance.name() + "' đã chạy (pid " + browser.pid()
			+ ", cổng " + browser.debugPort() + ").");
	}

	private void onLaunchFailed(ScreenInstance instance, Throwable throwable) {
		instance.releaseLaunch();
		instance.state(ScreenState.CRASHED);
		instance.failure(String.valueOf(throwable.getMessage()));
		instance.placeholderStale();

		logger.error("Không mở được trình duyệt cho '" + instance.name() + "'.", throwable);

		// a failed launch retries exactly like a died browser; without this the
		// screen sat in CRASHED forever because relaunchDue() never armed
		if (instance.scheduleRelaunch()) {
			logger.info("Sẽ thử mở lại '" + instance.name() + "' sau ít giây.");
		} else {
			logger.warn("Đã hết lượt thử cho '" + instance.name()
				+ "'. Dùng /lunatv power " + instance.name() + " on để thử lại.");
		}
	}

	private void onBrowserDied(ScreenInstance instance, Throwable throwable) {
		if (!instances.containsValue(instance)) {
			return;
		}

		closeBrowser(instance);
		audio.stop(instance.name());

		instance.state(ScreenState.CRASHED);
		instance.failure(String.valueOf(throwable.getMessage()));
		instance.placeholderStale();

		if (!instance.scheduleRelaunch()) {
			logger.warn("Màn hình '" + instance.name() + "' đã thử lại nhiều lần và dừng. Dùng /lunatv refresh.");

			return;
		}

		logger.warn("Trình duyệt của '" + instance.name() + "' chết, sẽ thử lại: " + throwable.getMessage());
	}

	/**
	 * Retries screens whose browser died and whose backoff has elapsed, and
	 * attaches screens whose world has since loaded. Called on a repeating task.
	 */
	public void tick() {
		for (ScreenInstance instance : instances.values()) {
			if (instance.state() == ScreenState.SUSPENDED) {
				if (Bukkit.getWorld(instance.screen().world()) != null) {
					bring(instance);
				}

				continue;
			}

			if (instance.state() == ScreenState.CRASHED && instance.powered() && instance.relaunchDue()) {
				launchBrowser(instance);
			}
		}
	}

	/**
	 * Removes a screen entirely.
	 *
	 * @param name the screen's name
	 * @return true when a screen was removed
	 */
	public boolean remove(String name) {
		Optional<ScreenInstance> found = find(name);

		if (found.isEmpty()) {
			return false;
		}

		ScreenInstance instance = found.get();

		instances.remove(key(instance.name()));

		// Each teardown step is isolated, and the display goes first. They used to
		// run in sequence with the browser first: one throw there (a JPEG reader
		// disposed off its owning thread) skipped the despawn entirely and left
		// map frames on every viewer's client that nothing owned any more.
		step(instance, "âm thanh", () -> audio.remove(instance.name()));
		step(instance, "hiển thị", () -> displays.detach(instance));
		step(instance, "trình duyệt", () -> closeBrowser(instance));
		persist();

		return true;
	}

	private void step(ScreenInstance instance, String what, Runnable action) {
		try {
			action.run();
		} catch (Throwable throwable) {
			logger.error("Lỗi khi dọn " + what + " của '" + instance.name() + "'.", throwable);
		}
	}

	/**
	 * Destroys MapEngine displays that no longer belong to any screen or panel.
	 *
	 * These are the leftovers of a teardown that failed partway: MapEngine still
	 * holds the display, and every viewer still has its frames, but nothing here
	 * references it, so no code path will ever despawn it.
	 *
	 * @param owned a test for displays that are still in use
	 * @return how many were destroyed
	 */
	public int cleanupOrphans(java.util.function.Predicate<IMapDisplay> owned) {
		int removed = 0;

		for (IMapDisplay display : java.util.List.copyOf(MapEngineApi.instance().mapDisplays())) {
			if (owned.test(display) || byDisplay(display).isPresent()) {
				continue;
			}

			for (Player player : Bukkit.getOnlinePlayers()) {
				try {
					display.despawn(player);
				} catch (Throwable ignored) {
					// a viewer who never saw it is not an error
				}
			}

			try {
				display.destroy();
				removed++;
			} catch (Throwable throwable) {
				logger.warn("Không huỷ được display mồ côi: " + throwable);
			}
		}

		return removed;
	}

	/**
	 * Points a screen at a new page.
	 *
	 * @param instance the screen
	 * @param url the address to open
	 */
	public void navigate(ScreenInstance instance, String url) {
		instance.screen().url(url);
		persist();

		CdpBrowser browser = instance.browser();

		if (browser != null) {
			browser.navigate(url);
		}
	}

	/**
	 * Turns a screen's sound on or off.
	 *
	 * @param instance the screen
	 * @param on true to stream its audio into voice chat
	 * @return the outcome, carrying the reason when audio is unavailable
	 */
	public Outcome audio(ScreenInstance instance, boolean on) {
		if (!on) {
			instance.screen().audio(false);
			audio.stop(instance.name());
			persist();

			return Outcome.ok();
		}

		String reason = audio.unavailableReason();

		if (reason != null) {
			return Outcome.failed(reason);
		}

		if (!instance.powered()) {
			return Outcome.failed("Màn hình đang tắt; bật nguồn trước đã.");
		}

		instance.screen().audio(true);
		persist();

		if (!startAudio(instance, audio.prepareSink(instance.name()))) {
			return Outcome.failed("Không mở được kênh âm thanh.");
		}

		return Outcome.ok();
	}

	private boolean startAudio(ScreenInstance instance, String sink) {
		Location at = center(instance);

		if (at == null) {
			return false;
		}

		if (!instance.screen().stereo()) {
			return audio.start(instance.name(), sink, at, null, instance.screen().volume());
		}

		Location[] speakers = speakers(instance, at);

		return audio.start(instance.name(), sink, speakers[0], speakers[1], instance.screen().volume());
	}

	/**
	 * Where a stereo screen's two channels sound from: its left and right edges
	 * as a viewer standing in front of it sees them.
	 *
	 * Left and right are the viewer's, not the world's, so the mapping depends
	 * on which way the wall faces: somebody looking at a south-facing screen is
	 * itself facing north, and their left hand points at -X. Getting this
	 * backwards would swap the channels for every screen on that axis, which is
	 * audible on anything mixed wide and invisible on anything else.
	 *
	 * The speakers sit slightly inside the edges: exactly on the boundary they
	 * are as far from a centred viewer as the separation is wide, which reads
	 * as two sources rather than one stage.
	 *
	 * @param instance the screen
	 * @param center its middle, already resolved
	 * @return two locations, left first
	 */
	public Location[] speakers(ScreenInstance instance, Location center) {
		IMapDisplay display = instance.display();
		BlockFace facing = instance.screen().facing();
		boolean alongX = facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			|| facing == BlockFace.UP || facing == BlockFace.DOWN;

		double min;
		double max;

		if (display != null) {
			BoundingBox box = display.box();

			min = alongX ? box.getMinX() : box.getMinZ();
			max = alongX ? box.getMaxX() : box.getMaxZ();
		} else {
			BlockVector a = instance.screen().cornerA();
			BlockVector b = instance.screen().cornerB();

			min = alongX ? Math.min(a.getX(), b.getX()) : Math.min(a.getZ(), b.getZ());
			max = alongX ? Math.max(a.getX(), b.getX()) : Math.max(a.getZ(), b.getZ());
		}

		double inset = (max - min) * SPEAKER_INSET;
		double low = min + inset;
		double high = max - inset;

		// a viewer of a north- or east-facing wall has their left toward +X / +Z;
		// on the opposite faces it is the other way round
		boolean leftAtHigh = facing == BlockFace.NORTH || facing == BlockFace.EAST;
		double left = leftAtHigh ? high : low;
		double right = leftAtHigh ? low : high;

		return new Location[] {
			at(center, alongX, left),
			at(center, alongX, right),
		};
	}

	private static Location at(Location center, boolean alongX, double value) {
		Location placed = center.clone();

		if (alongX) {
			placed.setX(value);
		} else {
			placed.setZ(value);
		}

		return placed;
	}

	/**
	 * Turns wheel scrolling on or off for a screen.
	 *
	 * @param instance the screen
	 * @param on true to let the hotbar wheel scroll the page
	 */
	public void scroll(ScreenInstance instance, boolean on) {
		instance.screen().scroll(on);
		persist();
	}

	/**
	 * Sets whether a screen's sound is split across two positioned channels.
	 *
	 * The recorder is asked for one channel or two when it starts, so a change
	 * restarts the stream; the picture is untouched.
	 *
	 * @param instance the screen
	 * @param on true for stereo
	 */
	public void stereo(ScreenInstance instance, boolean on) {
		instance.screen().stereo(on);
		persist();

		if (instance.screen().audio()) {
			audio.stop(instance.name());
			startAudio(instance, audio.prepareSink(instance.name()));
		}
	}

	/**
	 * The middle of a screen in the world, where its sound comes from.
	 *
	 * @param instance the screen
	 * @return the location, or null when its world is not loaded
	 */
	public Location center(ScreenInstance instance) {
		World world = Bukkit.getWorld(instance.screen().world());

		if (world == null) {
			return null;
		}

		IMapDisplay display = instance.display();

		if (display != null) {
			return display.box().getCenter().toLocation(world);
		}

		BlockVector a = instance.screen().cornerA();
		BlockVector b = instance.screen().cornerB();

		return new Location(world,
			(a.getX() + b.getX()) / 2.0,
			(a.getY() + b.getY()) / 2.0,
			(a.getZ() + b.getZ()) / 2.0);
	}

	/**
	 * Sets a screen's volume.
	 *
	 * @param instance the screen
	 * @param volume 0 to 100
	 */
	public void volume(ScreenInstance instance, int volume) {
		instance.screen().volume(volume);
		audio.volume(instance.name(), instance.screen().volume());
		persist();
	}

	/**
	 * Locks or unlocks a screen against clicks from players without permission.
	 *
	 * @param instance the screen
	 * @param locked true to freeze it
	 */
	/**
	 * Changes a screen's capture divisor, live when its browser is up.
	 *
	 * The browser re-lays the page out at the new size, so a video site also
	 * re-picks its stream quality; nothing is relaunched and the page keeps
	 * its state.
	 *
	 * @param instance the screen
	 * @param scale the divisor, clamped to 1..4
	 */
	/** The rate a screen actually runs at: its own fps, or the global default. */
	public int effectiveFps(TvScreen screen) {
		return screen.fps() > 0 ? screen.fps() : config.fps();
	}

	/** The bandwidth budget a screen actually gets: its own, or the global default. */
	public int effectiveMegabits(TvScreen screen) {
		return screen.maxMegabits() > 0 ? screen.maxMegabits() : config.maxMegabits();
	}

	/**
	 * Changes a screen's bandwidth budget; zero returns it to the global default.
	 *
	 * @param instance the screen
	 * @param megabits megabits per second, 0..1000
	 */
	public void maxMegabits(ScreenInstance instance, int megabits) {
		instance.screen().maxMegabits(megabits);
		persist();
	}

	/**
	 * Changes a screen's frame rate; zero returns it to the global default.
	 *
	 * Applied live: the browser's capture pacing follows immediately.
	 *
	 * @param instance the screen
	 * @param fps frames per second, 0..30
	 */
	public void fps(ScreenInstance instance, int fps) {
		instance.screen().fps(fps);

		CdpBrowser browser = instance.browser();

		if (browser != null) {
			browser.displayRate(effectiveFps(instance.screen()));
		}

		persist();
	}

	/**
	 * The dither mode a screen renders with, as a word for a human.
	 *
	 * @param screen the screen
	 * @return "theo chung (…)", "tắt", "bật (nhanh)" or "floyd (tốn CPU)"
	 */
	public String converterLabel(TvScreen screen) {
		if (screen.converter().isEmpty()) {
			return "theo chung (" + modeLabel(config.converter(), screen) + ")";
		}

		return modeLabel(screen.converter(), screen);
	}

	private String modeLabel(String mode, TvScreen screen) {
		return switch (mode.toUpperCase(Locale.ROOT)) {
			case "DIRECT" -> "tắt";
			case "FLOYD_STEINBERG" -> "floyd (tốn CPU)";
			default -> "bật (" + effectivePattern(screen) + ")";
		};
	}

	/**
	 * The ordered-dither pattern a screen renders with: its own, else the
	 * config's render.ordered-pattern.
	 *
	 * @param screen the screen
	 * @return bayer or a1..a4
	 */
	public String effectivePattern(TvScreen screen) {
		if (screen.ditherPattern().isEmpty()) {
			return config.orderedPattern();
		}

		return screen.ditherPattern();
	}

	/**
	 * Sets a screen's dither mode; an empty value returns it to the config's.
	 *
	 * Applied live: the pipeline's converter is swapped and a full redraw is
	 * requested, since the change means nothing until pixels are sent again.
	 *
	 * @param instance the screen
	 * @param converter DIRECT, FLOYD_STEINBERG, or empty to follow the config
	 */
	public void converter(ScreenInstance instance, String converter) {
		dither(instance, converter, null);
	}

	/**
	 * Sets a screen's dither mode and, optionally, its ordered pattern.
	 *
	 * Applied live: the pipeline's converter is swapped, the browser's own
	 * dither stage retuned, and a full redraw requested.
	 *
	 * @param instance the screen
	 * @param converter DIRECT, ORDERED, FLOYD_STEINBERG, or empty to follow the config
	 * @param pattern bayer or a1..a4, empty to follow the config, null to leave as is
	 */
	public void dither(ScreenInstance instance, String converter, String pattern) {
		instance.screen().converter(converter);

		if (pattern != null) {
			instance.screen().ditherPattern(pattern);
		}

		displays.applyRenderSettings(instance);
		persist();
	}

	/**
	 * Sets a screen's picture brightness, live.
	 *
	 * @param instance the screen
	 * @param brightness percentage, 50..200
	 */
	public void brightness(ScreenInstance instance, int brightness) {
		instance.screen().brightness(brightness);

		CdpBrowser browser = instance.browser();

		if (browser != null) {
			browser.brightness(instance.screen().brightness());
		}

		instance.requestRedraw();
		persist();
	}

	public void scale(ScreenInstance instance, int scale) {
		instance.screen().scale(scale);

		CdpBrowser browser = instance.browser();

		if (browser != null) {
			browser.rescale(instance.screen().scale());
		}

		instance.requestRedraw();
		persist();
	}

	/**
	 * Links or clears the redstone block that toggles a screen's power.
	 *
	 * @param instance the screen
	 * @param world block world, null to clear
	 * @param position block position, null to clear
	 */
	public void redstone(ScreenInstance instance, String world, org.bukkit.util.BlockVector position) {
		instance.screen().redstone(world, position);
		persist();
	}

	public void locked(ScreenInstance instance, boolean locked) {
		instance.screen().locked(locked);
		persist();
	}

	/** Shuts every screen down, for plugin disable. */
	public void shutdown() {
		for (ScreenInstance instance : instances.values()) {
			closeBrowser(instance);
			displays.detach(instance);
		}

		store.save(screens(), false);
		instances.clear();
	}

	private void closeBrowser(ScreenInstance instance) {
		CdpBrowser browser = instance.browser();

		instance.browser(null);

		if (browser != null) {
			browser.close();
		}
	}

	/** Writes the current screens to disk, off the main thread. */
	public void persist() {
		store.save(screens(), true);
	}

	private List<TvScreen> screens() {
		return instances.values().stream().map(ScreenInstance::screen).toList();
	}

	/**
	 * Clears what a screen's browser remembers: cookies, cache and storage.
	 *
	 * Two depths, because they answer different questions. The quick clear goes
	 * over CDP and keeps the browser running, so the page is back in a second;
	 * the deep one closes Chromium, deletes its profile directory and relaunches
	 * it, which is the only way to be rid of things no CDP domain covers
	 * (saved passwords, certificate decisions, the media licence store).
	 *
	 * @param instance the screen
	 * @param deep true to delete the profile and relaunch
	 * @return the outcome, with a reason when nothing could be cleared
	 */
	public Outcome clearBrowserData(ScreenInstance instance, boolean deep) {
		CdpBrowser browser = instance.browser();

		if (!deep) {
			if (browser == null) {
				return Outcome.failed("Màn hình chưa bật, không có gì để xoá. Dùng --sau để xoá cả profile.");
			}

			browser.clearData(true).exceptionally(throwable -> {
				logger.warn("Không xoá hết dữ liệu duyệt web của '" + instance.name() + "': " + throwable);

				return null;
			});

			return Outcome.ok();
		}

		boolean wasPowered = instance.powered();

		if (browser != null) {
			instance.browser(null);
			browser.close();
		}

		audio.stop(instance.name());

		Path profile = profileDir(instance.name());

		if (!deleteTree(profile)) {
			return Outcome.failed("Không xoá được thư mục profile của '" + instance.name() + "'.");
		}

		if (!wasPowered) {
			instance.state(ScreenState.OFF);
			instance.placeholderStale();

			return Outcome.ok();
		}

		launchBrowser(instance);

		return Outcome.ok();
	}

	/** Removes a directory and everything under it; true when nothing is left. */
	private boolean deleteTree(Path root) {
		if (!Files.exists(root)) {
			return true;
		}

		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			// deepest first, since a directory cannot be removed while it holds
			// anything
			for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}

			return true;
		} catch (IOException exception) {
			logger.warn("Không xoá được '" + root + "': " + exception.getMessage());

			return false;
		}
	}

	private Path profileDir(String screenName) {
		Path path = plugin.getDataFolder().toPath().resolve("profiles").resolve(key(screenName));

		try {
			Files.createDirectories(path);
		} catch (IOException exception) {
			logger.warn("Không tạo được thư mục profile cho '" + screenName + "': " + exception.getMessage());
		}

		return path;
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	/**
	 * Players near enough to be shown a screen.
	 *
	 * @param instance the screen
	 * @return the players within the configured spawn distance
	 */
	public List<Player> nearby(ScreenInstance instance) {
		Location center = center(instance);
		List<Player> found = new ArrayList<>();

		if (center == null) {
			return found;
		}

		double range = config.spawnDistance();
		BoundingBox box = boxOf(instance);

		for (Player player : center.getWorld().getPlayers()) {
			if (distanceToBox(box, player.getLocation()) <= range) {
				found.add(player);
			}
		}

		return found;
	}

	/**
	 * The screen's own volume in the world.
	 *
	 * Taken from the live display when there is one, because MapEngine knows the
	 * exact plane it laid out; otherwise derived from the stored corners.
	 *
	 * @param instance the screen
	 * @return its bounding box
	 */
	private static BoundingBox boxOf(ScreenInstance instance) {
		IMapDisplay display = instance.display();

		if (display != null) {
			return display.box();
		}

		BlockVector a = instance.screen().cornerA();
		BlockVector b = instance.screen().cornerB();

		return BoundingBox.of(a, b.clone().add(new org.bukkit.util.Vector(1, 1, 1)));
	}

	/**
	 * Distance from a point to the nearest part of a box, zero inside it.
	 *
	 * Measured against the box rather than the centre because a wide screen is
	 * not a point: standing in front of one end of a sixteen-block wall is eight
	 * blocks from its middle, and a centre-based radius would drop the display
	 * while the viewer is still looking straight at it.
	 *
	 * @param box the screen's volume
	 * @param at the point to measure from
	 * @return the distance in blocks
	 */
	private static double distanceToBox(BoundingBox box, Location at) {
		double dx = Math.max(0.0, Math.max(box.getMinX() - at.getX(), at.getX() - box.getMaxX()));
		double dy = Math.max(0.0, Math.max(box.getMinY() - at.getY(), at.getY() - box.getMaxY()));
		double dz = Math.max(0.0, Math.max(box.getMinZ() - at.getZ(), at.getZ() - box.getMaxZ()));

		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** The result of an operation that can fail with a reason for a human. */
	public record Outcome(boolean success, String reason) {

		static Outcome ok() {
			return new Outcome(true, null);
		}

		static Outcome failed(String reason) {
			return new Outcome(false, reason);
		}
	}
}
