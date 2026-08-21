package dev.belikhun.luna.tv.input;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.event.MapClickEvent;
import de.pianoman911.mapengine.api.util.MapClickType;
import de.pianoman911.mapengine.api.util.MapTraceResult;

import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;

/**
 * Turns a click on the wall into a click in the page.
 *
 * There are two ways in, because MapEngine's own event cannot reach a big
 * screen. Its left click comes from swing packets ray-traced against the
 * display's interact distance, but its right click rides on an interaction
 * entity, so the client only sends it within vanilla reach - about five blocks.
 * A 16x9 wall has to be viewed from twenty blocks back, where neither fires.
 *
 * So MapEngine's event is used when it arrives (it is the exact one), and a
 * player interaction is ray-traced here with {@code traceDisplayInView} when it
 * does not. The two are kept from doubling up by a short per-player guard.
 */
public final class MapClickListener implements Listener {

	/** Permission that can still click a locked screen. */
	public static final String CONTROL_PERMISSION = "lunatv.control";

	/** How long after a MapEngine click the fallback stays out of the way. */
	private static final long ENGINE_CLICK_GRACE_MS = 200L;
	/** Minimum gap between two delivered clicks from one player, either path. */
	private static final long CLICK_INTERVAL_MS = 150L;

	/** Dust shown where a left click landed. */
	private static final Particle.DustOptions LEFT_DUST =
		new Particle.DustOptions(Color.fromRGB(0xFF, 0x55, 0x55), 1.1f);
	/** Dust shown where a right click landed. */
	private static final Particle.DustOptions RIGHT_DUST =
		new Particle.DustOptions(Color.fromRGB(0x55, 0xAA, 0xFF), 1.1f);

	private final JavaPlugin plugin;
	private final ScreenManager screens;
	private final Map<UUID, Long> engineClicks = new ConcurrentHashMap<>();
	private final Map<UUID, Long> deliveredClicks = new ConcurrentHashMap<>();

	private volatile TvConfig config;

	public MapClickListener(JavaPlugin plugin, ScreenManager screens, TvConfig config) {
		this.plugin = plugin;
		this.screens = screens;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	@EventHandler
	public void onMapClick(MapClickEvent event) {
		engineClicks.put(event.player().getUniqueId(), System.currentTimeMillis());
		TvDebug.log("mapengine-click player=" + event.player().getName()
			+ " at=" + event.x() + "," + event.y() + " type=" + event.clickType());

		Optional<ScreenInstance> found = screens.byDisplay(event.display());

		if (found.isEmpty()) {
			TvDebug.log("mapengine-click ignored: display không thuộc luna-tv");

			return;
		}

		deliver(found.get(), event.player(), event.x(), event.y(),
			event.clickType() == MapClickType.RIGHT_CLICK, event.worldPos());
	}

	/**
	 * The ranged path: a plain interaction, ray-traced onto whatever screen the
	 * player is looking at.
	 */
	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		// the off hand fires a second event for the same physical click
		if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		Action action = event.getAction();
		boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;

		if (!right && !left) {
			return;
		}

		Player player = event.getPlayer();

		if (recent(engineClicks, player, ENGINE_CLICK_GRACE_MS)) {
			return;
		}

		if (recent(deliveredClicks, player, CLICK_INTERVAL_MS)) {
			return;
		}

		MapTraceResult trace = trace(player);

		if (trace == null) {
			TvDebug.sampled("trace-miss:" + player.getName(), 20,
				"interact player=" + player.getName() + " right=" + right
				+ " -> tia không cắt màn hình nào (reach=" + config.interactDistance() + ")");

			return;
		}

		Optional<ScreenInstance> found = screens.byDisplay(trace.display());

		if (found.isEmpty()) {
			TvDebug.log("interact ignored: display không thuộc luna-tv");

			return;
		}

		TvDebug.log("interact-click player=" + player.getName() + " screen=" + found.get().name()
			+ " at=" + trace.viewPos().x() + "," + trace.viewPos().y()
			+ " right=" + right + " dist=" + String.format("%.1f", trace.clickDistance()));

		deliver(found.get(), player, trace.viewPos().x(), trace.viewPos().y(), right, trace.worldPos());
	}

	private MapTraceResult trace(Player player) {
		int reach = (int) Math.ceil(config.interactDistance());

		try {
			return MapEngineApi.instance().traceDisplayInView(player, Math.max(1, reach));
		} catch (Throwable throwable) {
			return null;
		}
	}

	private void deliver(ScreenInstance instance, Player player, int x, int y, boolean right, Location at) {
		if (instance.state() != ScreenState.RUNNING) {
			TvDebug.log("click dropped: screen=" + instance.name() + " state=" + instance.state());

			return;
		}

		if (instance.screen().locked() && !player.hasPermission(CONTROL_PERMISSION)) {
			TvDebug.log("click dropped: screen=" + instance.name() + " đã khoá");

			return;
		}

		CdpBrowser browser = instance.browser();

		if (browser == null) {
			TvDebug.log("click dropped: screen=" + instance.name() + " chưa có trình duyệt");

			return;
		}

		// MapEngine's own debounce is looser than advertised: one physical click
		// can arrive as a burst of identical events in the same tick, and six
		// rapid press/release pairs on a link is how a navigation gets cancelled.
		// One guard covers both delivery paths.
		if (recent(deliveredClicks, player, CLICK_INTERVAL_MS)) {
			TvDebug.log("click deduped: screen=" + instance.name() + " player=" + player.getName());

			return;
		}

		deliveredClicks.put(player.getUniqueId(), System.currentTimeMillis());
		browser.click(clamp(x, browser.width()), clamp(y, browser.height()), right);
		feedback(instance, at, right);
	}

	/**
	 * A puff of coloured dust where the click landed, so a click on a wall
	 * across the room visibly registers: red for left, blue for right.
	 *
	 * Scheduled onto the main thread because MapEngine's click event arrives on
	 * a network thread, and shown to everyone nearby - the screen is shared, so
	 * the feedback is too.
	 *
	 * @param instance the screen that was clicked
	 * @param at where on the wall, in world space
	 * @param right whether it was a right click
	 */
	private void feedback(ScreenInstance instance, Location at, boolean right) {
		if (at == null || at.getWorld() == null) {
			return;
		}

		// nudged off the plane toward the viewer, so the dust is not buried
		// inside the invisible frame
		var facing = instance.screen().facing();
		Location shown = at.clone().add(
			facing.getModX() * 0.12, facing.getModY() * 0.12, facing.getModZ() * 0.12);

		Particle.DustOptions dust = right ? RIGHT_DUST : LEFT_DUST;

		plugin.getServer().getScheduler().runTask(plugin, () ->
			shown.getWorld().spawnParticle(Particle.DUST, shown, 10, 0.08, 0.08, 0.08, 0.0, dust));
	}

	private static boolean recent(Map<UUID, Long> stamps, Player player, long withinMillis) {
		Long last = stamps.get(player.getUniqueId());

		return last != null && System.currentTimeMillis() - last < withinMillis;
	}

	private static int clamp(int value, int limit) {
		return Math.max(0, Math.min(limit - 1, value));
	}
}
