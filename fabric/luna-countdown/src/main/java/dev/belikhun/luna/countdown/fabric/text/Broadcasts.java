package dev.belikhun.luna.countdown.fabric.text;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sending one line to everyone online.
 *
 * Both calls hop onto the server thread themselves, so a caller ticking on its
 * own scheduler does not have to remember to.
 *
 * The action bar goes out as a packet rather than through the player's own
 * display helper: that helper is gone from the 26.x line, while this packet is
 * the wire format on every version this mod runs on.
 */
public final class Broadcasts {
	private Broadcasts() {
	}

	/** A system message in chat, to every player on the server. */
	public static void chat(MinecraftServer server, String message) {
		if (server == null) {
			return;
		}

		Component component = Component.literal(message == null ? "" : message);

		server.execute(() -> server.getPlayerList().broadcastSystemMessage(component, false));
	}

	/** One line above the hotbar, to every player on the server. */
	public static void actionBar(MinecraftServer server, String message) {
		if (server == null) {
			return;
		}

		Component component = Component.literal(message == null ? "" : message);

		server.execute(() -> {
			ClientboundSetActionBarTextPacket packet = new ClientboundSetActionBarTextPacket(component);

			for (ServerPlayer player : PlayerLookup.all(server)) {
				player.connection.send(packet);
			}
		});
	}
}
