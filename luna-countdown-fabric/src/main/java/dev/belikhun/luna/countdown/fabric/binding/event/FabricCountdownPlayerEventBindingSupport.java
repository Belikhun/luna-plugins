package dev.belikhun.luna.countdown.fabric.binding.event;

import dev.belikhun.luna.countdown.fabric.service.FabricCountdownService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricCountdownPlayerEventBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private FabricCountdownPlayerEventBindingSupport() {
	}

	public static boolean register(FabricCountdownService countdownService) {
		if (countdownService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> countdownService.handlePlayerJoin(resolvePlayerId(handler.getPlayer())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> countdownService.handlePlayerQuit(resolvePlayerId(handler.getPlayer())));
		return true;
	}

	private static UUID resolvePlayerId(net.minecraft.server.level.ServerPlayer player) {
		return player == null ? null : player.getUUID();
	}
}
