package dev.belikhun.luna.core.mc12.placeholder;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Re-samples the shared statistics on a timer, off the resolution path.
 *
 * Counting every entity and chunk is worth doing once a second and never worth
 * doing per lookup: a tab list asks for dozens of placeholders per player per
 * refresh, and they all want the same numbers. Sampling here means a lookup is a
 * map read.
 */
public final class PlaceholderRefreshListener {
	/** One second at 20 TPS; the tab list refreshes no faster than this. */
	private static final int TICKS_BETWEEN_SAMPLES = 20;

	private final LegacyPlaceholderService placeholders;

	private int ticksSinceSample;

	public PlaceholderRefreshListener(LegacyPlaceholderService placeholders) {
		this.placeholders = placeholders;
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}

		ticksSinceSample += 1;

		if (ticksSinceSample < TICKS_BETWEEN_SAMPLES) {
			return;
		}

		ticksSinceSample = 0;
		placeholders.refreshSharedSnapshot();
	}
}
