package dev.belikhun.luna.core.mc12.runtime;

import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatPublisher;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Times the server tick for the heartbeat's tick indices.
 *
 * Paired around the tick rather than counting the gap between two of them: the
 * gap is the tick *period*, which on a healthy server is a flat 50 ms of mostly
 * sleeping, and an index built on that would score every server as exactly
 * satisfied. What is wanted is the tick's own cost.
 */
public final class LegacyTickListener {
	private final BackendHeartbeatPublisher publisher;

	public LegacyTickListener(BackendHeartbeatPublisher publisher) {
		this.publisher = publisher;
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (publisher == null) {
			return;
		}

		if (event.phase == TickEvent.Phase.START) {
			publisher.tickStarted();
		} else {
			publisher.tickEnded();
		}
	}
}
