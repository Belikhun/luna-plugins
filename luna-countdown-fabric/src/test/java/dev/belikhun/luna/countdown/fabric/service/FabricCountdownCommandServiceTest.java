package dev.belikhun.luna.countdown.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCountdownCommandServiceTest {

	@Test
	void startValidatesDuration() {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		try {
			FabricCountdownCommandService commandService = new FabricCountdownCommandService(service);
			FabricCountdownCommandService.CommandResult result = commandService.start("abc", "Event");
			assertFalse(result.success());
		} finally {
			service.close();
		}
	}

	@Test
	void startAndStopFlowWorks() {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		try {
			FabricCountdownCommandService commandService = new FabricCountdownCommandService(service);
			FabricCountdownCommandService.CommandResult start = commandService.start("2s", "Boss");
			assertTrue(start.success());
			assertEquals(1, commandService.snapshots().size());

			int id = commandService.snapshots().iterator().next().id();
			FabricCountdownCommandService.CommandResult stop = commandService.stop(id);
			assertTrue(stop.success());
			assertEquals(0, commandService.snapshots().size());
		} finally {
			service.close();
		}
	}

	@Test
	void stopAllClearsActiveCountdowns() {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		try {
			FabricCountdownCommandService commandService = new FabricCountdownCommandService(service);
			commandService.start("10", "One");
			commandService.start("10", "Two");
			assertEquals(2, commandService.snapshots().size());

			FabricCountdownCommandService.CommandResult stopAll = commandService.stopAll();
			assertTrue(stopAll.success());
			assertEquals(0, commandService.snapshots().size());
		} finally {
			service.close();
		}
	}

	@Test
	void completedCountdownRemovesTitleMetadata() throws Exception {
		FabricCountdownService service = new FabricCountdownService(testLogger());
		try {
			FabricCountdownCommandService commandService = new FabricCountdownCommandService(service);
			FabricCountdownCommandService.CommandResult start = commandService.start("1s", "Quick");
			assertTrue(start.success());
			assertEquals(1, commandService.titlesById().size());

			Thread.sleep(1700L);
			assertEquals(0, commandService.titlesById().size());
		} finally {
			service.close();
		}
	}

	private static LunaLogger testLogger() {
		return LunaLogger.forLogger(Logger.getLogger("FabricCountdownCommandServiceTest"), false);
	}
}
