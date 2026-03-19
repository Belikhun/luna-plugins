package dev.belikhun.luna.countdown.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCountdownServiceTest {

	@Test
	void countdownCompletesAndAutoRemoves() throws Exception {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		CountDownLatch completed = new CountDownLatch(1);
		AtomicInteger updates = new AtomicInteger();
		try {
			service.start("Test", 1, new FabricCountdownService.CountdownListener() {
				@Override
				public void onBegin(FabricCountdownService.CountdownSnapshot snapshot) {
				}

				@Override
				public void onUpdate(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds, double progress) {
					updates.incrementAndGet();
				}

				@Override
				public void onComplete(FabricCountdownService.CountdownSnapshot snapshot) {
					completed.countDown();
				}

				@Override
				public void onStop(FabricCountdownService.CountdownSnapshot snapshot, String reason) {
				}
			});

			assertTrue(completed.await(4, TimeUnit.SECONDS));
			assertTrue(updates.get() > 0);
			Thread.sleep(5500L);
			assertEquals(0, service.snapshots().size());
		} finally {
			service.close();
		}
	}

	@Test
	void stopRemovesActiveCountdown() {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		AtomicBoolean stopped = new AtomicBoolean(false);
		try {
			FabricCountdownService.ActiveCountdown active = service.start("StopMe", 20, new FabricCountdownService.CountdownListener() {
				@Override
				public void onBegin(FabricCountdownService.CountdownSnapshot snapshot) {
				}

				@Override
				public void onUpdate(FabricCountdownService.CountdownSnapshot snapshot, double remainSeconds, double progress) {
				}

				@Override
				public void onComplete(FabricCountdownService.CountdownSnapshot snapshot) {
				}

				@Override
				public void onStop(FabricCountdownService.CountdownSnapshot snapshot, String reason) {
					stopped.set(true);
				}
			});

			assertTrue(service.stop(active.id(), "manual"));
			assertTrue(stopped.get());
			assertEquals(0, service.snapshots().size());
		} finally {
			service.close();
		}
	}

	@Test
	void tracksOnlinePlayerJoinQuitState() {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		try {
			UUID playerA = UUID.randomUUID();
			UUID playerB = UUID.randomUUID();

			service.handlePlayerJoin(playerA);
			service.handlePlayerJoin(playerB);
			service.handlePlayerJoin(playerA);
			assertEquals(2, service.onlinePlayerCount());

			service.handlePlayerQuit(playerA);
			assertEquals(1, service.onlinePlayerCount());
		} finally {
			service.close();
		}
	}

	private static LunaLogger testLogger() {
		return LunaLogger.forLogger(Logger.getLogger("FabricCountdownServiceTest"), false);
	}
}
