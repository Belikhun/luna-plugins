package dev.belikhun.luna.core.mc.compat;

import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Chat json to a game component, for the lines carrying the static serializer:
 * 1.19 through 1.20.2.
 *
 * 1.20.3 replaced it with a registry-aware codec, which is text-codec. The server
 * is accepted and unused here to keep one signature across both.
 */
public final class ComponentJson {
	private ComponentJson() {
	}

	/** Parse chat json. Throws {@link IllegalArgumentException} on malformed input. */
	public static Component parse(MinecraftServer server, JsonElement json) {
		Component parsed = Component.Serializer.fromJson(json);

		if (parsed == null) {
			throw new IllegalArgumentException("Không thể chuyển Adventure component sang Minecraft component: json không hợp lệ");
		}

		return parsed;
	}
}
