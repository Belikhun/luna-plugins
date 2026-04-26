package dev.belikhun.luna.core.fabric.util;

import net.minecraft.server.level.ServerPlayer;

public final class FabricPlayerNames {
	private FabricPlayerNames() {
	}

	public static String resolve(ServerPlayer player) {
		if (player == null) {
			return "";
		}

		String name = player.getName().getString();
		if (name != null && !name.isBlank()) {
			return name;
		}

		name = player.getScoreboardName();
		if (name != null && !name.isBlank()) {
			return name;
		}

		return player.getUUID().toString();
	}
}
