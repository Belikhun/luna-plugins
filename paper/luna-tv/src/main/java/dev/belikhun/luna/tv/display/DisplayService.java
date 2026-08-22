package dev.belikhun.luna.tv.display;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.pipeline.IPipelineContext;
import de.pianoman911.mapengine.api.util.Converter;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.TvScreen;

/**
 * Everything that talks to MapEngine.
 *
 * One drawing space per screen, shared by every viewer: the ARGB to map-palette
 * dither is the expensive step and it does not depend on who is watching.
 * MapEngine's own per-player buffering is what turns that one dithered frame
 * into per-viewer deltas, so a second viewer costs bandwidth, not CPU.
 */
public final class DisplayService {

	/**
	 * How many frame buffers each screen cycles through.
	 *
	 * Enough that a buffer is only rewritten several frames after its flush was
	 * queued, which is far longer than a conversion takes, without holding many
	 * copies of a large screen in memory.
	 */
	private static final int BUFFER_ROTATION = 4;

	private final LunaLogger logger;

	private volatile TvConfig config;

	public DisplayService(LunaLogger logger, TvConfig config) {
		this.logger = logger;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/**
	 * Whether MapEngine is present and answering.
	 *
	 * @return true when {@link MapEngineApi#instance()} resolves
	 */
	public static boolean available() {
		try {
			return MapEngineApi.instance() != null;
		} catch (Throwable throwable) {
			return false;
		}
	}

	/**
	 * Builds the map display and drawing space for a screen.
	 *
	 * Must run on the main thread: MapEngine reads world state to lay the frames
	 * out.
	 *
	 * @param instance the screen to attach a display to
	 * @return true when a display was created, false when its world is missing
	 */
	public boolean attach(ScreenInstance instance) {
		TvScreen screen = instance.screen();
		World world = Bukkit.getWorld(screen.world());

		if (world == null) {
			return false;
		}

		MapEngineApi api = MapEngineApi.instance();
		IMapDisplay display = api.displayProvider()
			.createBasic(screen.cornerA(), screen.cornerB(), screen.facing());

		display.interactDistance(config.interactDistance());

		// one context, several buffers: the context carries the receivers and the
		// per-player delta cache, while the buffers rotate so MapEngine's own
		// worker is never converting a buffer that the render thread has begun
		// overwriting
		IPipelineContext ctx = api.pipeline().createCtx(display);

		ctx.converter(converter(instance));
		// per-player deltas: MapEngine caches what each viewer already has, so only
		// the maps whose pixels changed go out
		ctx.buffering(true);
		// one bundle per frame keeps a multi-map wall from tearing mid-update, but
		// bundles are also the one exotic packet shape in this path, so the switch
		// exists to take them out of the equation entirely
		ctx.bundling(config.bundling());

		IDrawingSpace[] drawings = new IDrawingSpace[BUFFER_ROTATION];

		for (int index = 0; index < drawings.length; index++) {
			FullSpacedColorBuffer buffer = new FullSpacedColorBuffer(
				display.pixelWidth(), display.pixelHeight());

			drawings[index] = api.pipeline().drawingSpace(ctx, buffer);
		}

		instance.display(display);
		instance.pipeline(ctx, drawings);

		TvDebug.log("attach screen=" + instance.name()
			+ " maps=" + display.width() + "x" + display.height()
			+ " px=" + display.pixelWidth() + "x" + display.pixelHeight()
			+ " box=" + display.box()
			+ " converter=" + converter(instance) + " interactDistance=" + display.interactDistance());

		return true;
	}

	/**
	 * Re-applies the settings that live on an existing display, so a config
	 * reload changes a running screen instead of only the next one.
	 *
	 * A full redraw is requested as well: the converter decides how every pixel
	 * is quantised, so the change means nothing until a frame is sent again, and
	 * on a static page there may never be another one.
	 *
	 * @param instance the screen to re-apply to
	 */
	public void applyRenderSettings(ScreenInstance instance) {
		IMapDisplay display = instance.display();
		IPipelineContext ctx = instance.ctx();

		if (display != null) {
			display.interactDistance(config.interactDistance());
		}

		if (ctx == null) {
			return;
		}

		ctx.converter(converter(instance));
		ctx.bundling(config.bundling());

		CdpBrowser browser = instance.browser();

		if (browser != null) {
			browser.dither(browserPattern(instance));
		}

		instance.requestRedraw();
	}

	/**
	 * The dither mode a screen actually renders with: its own, else the config's.
	 *
	 * @param instance the screen, or null to resolve the global default
	 * @return DIRECT, ORDERED or FLOYD_STEINBERG
	 */
	public String mode(ScreenInstance instance) {
		String own = instance == null ? "" : instance.screen().converter();
		String name = (own.isEmpty() ? config.converter() : own).toUpperCase(Locale.ROOT);

		return switch (name) {
			case "DIRECT", "ORDERED", "FLOYD_STEINBERG" -> name;
			default -> {
				logger.warn("render.converter không hợp lệ: " + name + ", dùng ORDERED.");

				yield "ORDERED";
			}
		};
	}

	/**
	 * The dither pattern a screen's browser should apply in the decode pass.
	 *
	 * Only the ORDERED mode dithers there; the pattern is the screen's own
	 * when set, else the config's render.ordered-pattern.
	 *
	 * @param instance the screen
	 * @return bayer or a1..a4, or an empty string when the browser must not dither
	 */
	public String browserPattern(ScreenInstance instance) {
		if (!"ORDERED".equals(mode(instance))) {
			return "";
		}

		String own = instance.screen().ditherPattern();

		if (own.isEmpty()) {
			return config.orderedPattern();
		}

		return own;
	}

	/**
	 * The MapEngine converter for a screen's resolved mode.
	 *
	 * ORDERED maps to DIRECT here: the dithering happened upstream in the
	 * decode pass, so MapEngine only does the nearest-colour lookup.
	 *
	 * @param instance the screen, or null to resolve the global default
	 * @return the converter to install on the pipeline
	 */
	public Converter converter(ScreenInstance instance) {
		if ("FLOYD_STEINBERG".equals(mode(instance))) {
			return Converter.FLOYD_STEINBERG;
		}

		return Converter.DIRECT;
	}

	/**
	 * Tears a screen's display down and stops sending it to anyone.
	 *
	 * @param instance the screen to detach
	 */
	public void detach(ScreenInstance instance) {
		IMapDisplay display = instance.display();

		if (display == null) {
			return;
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			if (instance.viewers().contains(player.getUniqueId())) {
				display.despawn(player);
			}
		}

		instance.viewers().clear();
		instance.pipeline(null, null);
		instance.display(null);

		try {
			display.destroy();
		} catch (Throwable throwable) {
			logger.warn("Không huỷ được display của '" + instance.name() + "': " + throwable);
		}
	}

	/**
	 * Shows a screen to a player and adds them to its receivers.
	 *
	 * @param instance the screen
	 * @param player the viewer to add
	 */
	public void show(ScreenInstance instance, Player player) {
		IMapDisplay display = instance.display();
		IPipelineContext ctx = instance.ctx();

		if (display == null || ctx == null) {
			return;
		}

		display.spawn(player);

		// players compare equal by identity contract across relogs, so after a
		// rejoin the set may still hold the previous session's object; adding the
		// new one would be a no-op and every frame would go to a dead connection.
		// Removing first guarantees the object in the set is the live one.
		ctx.removeReceiver(player);
		ctx.addReceiver(player);
		TvDebug.log("spawn screen=" + instance.name() + " player=" + player.getName()
			+ "@" + Integer.toHexString(System.identityHashCode(player))
			+ " receivers=" + ctx.receivers().size());
		instance.viewers().add(player.getUniqueId());
		// a joiner would otherwise stare at empty maps until the page repaints, which
		// on a static page is never
		instance.placeholderStale();
		instance.requestRedraw();
	}

	/**
	 * Hides a screen from a player.
	 *
	 * @param instance the screen
	 * @param player the viewer to drop
	 */
	public void hide(ScreenInstance instance, Player player) {
		IMapDisplay display = instance.display();
		IPipelineContext ctx = instance.ctx();

		instance.viewers().remove(player.getUniqueId());

		if (display == null || ctx == null) {
			return;
		}

		ctx.removeReceiver(player);
		display.despawn(player);
		TvDebug.log("despawn screen=" + instance.name() + " player=" + player.getName()
			+ " receivers=" + ctx.receivers().size());
	}
}
