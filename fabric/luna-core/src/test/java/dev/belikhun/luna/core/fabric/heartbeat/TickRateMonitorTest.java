package dev.belikhun.luna.core.fabric.heartbeat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor stands in for a game method that keeps being renamed, so its
 * arithmetic is worth pinning: a wrong reading here is a wrong TPS on every
 * fabric backend in the console.
 */
class TickRateMonitorTest {
	@Test
	void reportsFullRateBeforeItHasSamples() {
		assertEquals(20D, new TickRateMonitor().tps());
	}

	@Test
	void measuresTheRateItIsTicked() throws InterruptedException {
		TickRateMonitor monitor = new TickRateMonitor();

		// ten ticks 10ms apart is a 100/s pace, which the cap pulls back to 20
		for (int tick = 0; tick < 10; tick++) {
			monitor.onTick();
			Thread.sleep(10L);
		}

		assertEquals(20D, monitor.tps());
	}

	@Test
	void reportsALaggingServerBelowFullRate() throws InterruptedException {
		TickRateMonitor monitor = new TickRateMonitor();

		// four ticks 100ms apart is 10 per second, half the target
		for (int tick = 0; tick < 4; tick++) {
			monitor.onTick();
			Thread.sleep(100L);
		}

		double tps = monitor.tps();

		assertTrue(tps > 5D && tps < 15D, "expected roughly 10 tps, got " + tps);
	}

	@Test
	void keepsMeasuringOnceTheWindowHasWrapped() {
		TickRateMonitor monitor = new TickRateMonitor();

		for (int tick = 0; tick < 250; tick++) {
			monitor.onTick();
		}

		// every tick landed in the same instant, so the window is degenerate; the
		// monitor must still answer a usable number rather than dividing by zero
		double tps = monitor.tps();

		assertTrue(tps >= 0D && tps <= 20D, "expected a rate inside the range, got " + tps);
	}
}
