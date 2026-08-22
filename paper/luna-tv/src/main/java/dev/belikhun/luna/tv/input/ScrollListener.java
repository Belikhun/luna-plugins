package dev.belikhun.luna.tv.input;

import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.util.MapTraceResult;

import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;

/**
 * The mouse wheel, borrowed from the hotbar.
 *
 * There is no scroll packet in Minecraft, but changing the selected hotbar slot
 * is the wheel, so a slot change while sneaking and looking at a screen is read
 * as a scroll at whatever pixel the player is aiming at. The slot change itself
 * is then cancelled: somebody reading a page is not trying to swap their
 * pickaxe, and leaving it through would rearrange their hand on every line.
 *
 * Sneak is what makes the gesture unambiguous. A bare wheel is how a player
 * picks a tool, and it is also indistinguishable here from a number key, so
 * without a modifier the screen would steal both. Holding sneak says the wheel
 * is meant for the screen and nothing else does.
 *
 * Only cancelled when the scroll was actually used. Not sneaking, looking away,
 * out of range, a screen that is off or locked - all of those leave the hotbar
 * alone and behave exactly as vanilla.
 */
public final class ScrollListener implements Listener {

	/** Hotbar slots, which is what makes the wrap arithmetic necessary. */
	private static final int SLOTS = 9;

	private final ScreenManager screens;

	private volatile TvConfig config;

	public ScrollListener(ScreenManager screens, TvConfig config) {
		this.screens = screens;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	@EventHandler(ignoreCancelled = true)
	public void onItemHeld(PlayerItemHeldEvent event) {
		int notches = notches(event.getPreviousSlot(), event.getNewSlot());

		if (notches == 0) {
			return;
		}

		Player player = event.getPlayer();

		// the modifier that separates "scroll the page" from "pick a tool"
		if (!player.isSneaking()) {
			return;
		}

		MapTraceResult trace = trace(player);

		if (trace == null) {
			return;
		}

		Optional<ScreenInstance> found = screens.byDisplay(trace.display());

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();

		// off means the hotbar is untouched, so the slot change goes through
		if (!instance.screen().scroll()) {
			return;
		}

		if (instance.state() != ScreenState.RUNNING) {
			return;
		}

		if (instance.screen().locked()
			&& !player.hasPermission(MapClickListener.CONTROL_PERMISSION)) {
			return;
		}

		CdpBrowser browser = instance.browser();

		if (browser == null) {
			return;
		}

		int x = clamp(trace.viewPos().x(), browser.width());
		int y = clamp(trace.viewPos().y(), browser.height());
		// down the hotbar is down the page, which is what the wheel does
		// everywhere else
		int delta = notches * config.scrollStep() * (config.invertScroll() ? -1 : 1);

		browser.scroll(x, y, delta);
		event.setCancelled(true);

		TvDebug.log("scroll screen=" + instance.name() + " player=" + player.getName()
			+ " at=" + x + "," + y + " notches=" + notches + " delta=" + delta);
	}

	/**
	 * Wheel notches between two hotbar slots, as a signed number.
	 *
	 * The hotbar is a ring, so slot 8 to slot 0 is one notch forward rather
	 * than eight back. Anything further than half way round is therefore read
	 * as the short way in the opposite direction; a jump of exactly four or
	 * fewer is taken at face value, which is also what a number key press
	 * looks like - those are indistinguishable from a wheel here, and treating
	 * one as a scroll is the accepted cost of having a wheel at all.
	 *
	 * @param previous the slot being left
	 * @param current the slot arrived at
	 * @return notches, positive when scrolling down the hotbar
	 */
	public static int notches(int previous, int current) {
		int delta = current - previous;

		if (delta > SLOTS / 2) {
			delta -= SLOTS;
		} else if (delta < -(SLOTS / 2)) {
			delta += SLOTS;
		}

		return delta;
	}

	private MapTraceResult trace(Player player) {
		int reach = (int) Math.ceil(config.interactDistance());

		try {
			return MapEngineApi.instance().traceDisplayInView(player, Math.max(1, reach));
		} catch (Throwable throwable) {
			return null;
		}
	}

	private static int clamp(int value, int limit) {
		return Math.max(0, Math.min(limit - 1, value));
	}
}
