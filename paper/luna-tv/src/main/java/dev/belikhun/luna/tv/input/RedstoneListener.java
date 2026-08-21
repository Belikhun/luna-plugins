package dev.belikhun.luna.tv.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.TvScreen;

/**
 * Physical power control: a rising redstone edge at a linked block toggles
 * its screen.
 *
 * Edge semantics fit every practical component with one rule: a button's
 * press toggles (its release is a falling edge and does nothing), a lever
 * toggles on each up-flip, a pressure plate on each step. Level-following
 * was rejected because a button's pulse would flick the screen on for one
 * second and off again.
 *
 * BlockRedstoneEvent fires constantly across the world, so the lookup is a
 * prebuilt map refreshed only when links change.
 */
public final class RedstoneListener implements Listener {

	private final ScreenManager screens;
	private final LunaLogger logger;
	private final Map<String, String> links = new ConcurrentHashMap<>();

	public RedstoneListener(ScreenManager screens, LunaLogger logger) {
		this.screens = screens;
		this.logger = logger;
	}

	/** Rebuilds the position index; call after any link change or load. */
	public void refresh() {
		links.clear();

		for (ScreenInstance instance : screens.instances()) {
			TvScreen screen = instance.screen();

			if (screen.redstone() == null) {
				continue;
			}

			links.put(key(screen.redstoneWorld(),
				screen.redstone().getBlockX(),
				screen.redstone().getBlockY(),
				screen.redstone().getBlockZ()), screen.name());
		}
	}

	@EventHandler
	public void onRedstone(BlockRedstoneEvent event) {
		if (links.isEmpty()) {
			return;
		}

		// only a rising edge acts; level changes while powered are ignored
		if (event.getOldCurrent() != 0 || event.getNewCurrent() <= 0) {
			return;
		}

		Block block = event.getBlock();
		String screenName = links.get(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));

		if (screenName == null) {
			return;
		}

		screens.find(screenName).ifPresent(instance -> {
			boolean on = !instance.powered();
			ScreenManager.Outcome outcome = screens.power(instance, on);

			if (outcome.success()) {
				logger.info("Redstone " + (on ? "bật" : "tắt") + " màn hình '" + screenName + "'.");
			}
		});
	}

	private static String key(String world, int x, int y, int z) {
		return world + ":" + x + ":" + y + ":" + z;
	}
}
