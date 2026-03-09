package dev.belikhun.luna.smp.packprotect;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.smp.packprotect.messaging.PackLoadState;
import dev.belikhun.luna.smp.packprotect.messaging.PackLoadStateMessage;

public final class PackLoadStateMessageListener {
	private final LunaLogger logger;
	private final PackLoadProtectionManager protectionManager;

	public PackLoadStateMessageListener(LunaLogger logger, PackLoadProtectionManager protectionManager) {
		this.logger = logger.scope("PackProtectSync");
		this.protectionManager = protectionManager;
	}

	public PluginMessageDispatchResult handle(byte[] payload) {
		try {
			PackLoadStateMessage message = PackLoadStateMessage.readFrom(dev.belikhun.luna.core.api.messaging.PluginMessageReader.of(payload));
			if (message.state() == PackLoadState.STARTED) {
				protectionManager.enable(message.playerId(), message.playerName());
				return PluginMessageDispatchResult.HANDLED;
			}

			if (message.state() == PackLoadState.COMPLETED) {
				protectionManager.disable(message.playerId(), message.playerName());
				return PluginMessageDispatchResult.HANDLED;
			}

			logger.warn("Nhận trạng thái pack không hỗ trợ: " + message.state());
		} catch (RuntimeException exception) {
			logger.error("Không thể parse pack-load sync message.", exception);
		}
		return PluginMessageDispatchResult.HANDLED;
	}
}
