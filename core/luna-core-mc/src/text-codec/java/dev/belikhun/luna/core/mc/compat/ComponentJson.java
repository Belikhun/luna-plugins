package dev.belikhun.luna.core.mc.compat;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;

/**
 * Chat json to a game component, for the lines whose serializer is a registry-aware
 * codec: 1.20.3 through 26.x.
 *
 * A component can name things the registries own, which is why the codec wants a
 * serialization context; older lines had a static serializer that took none and
 * take text-serializer instead.
 */
public final class ComponentJson {
	private ComponentJson() {
	}

	/** Parse chat json. Throws {@link IllegalArgumentException} on malformed input. */
	public static Component parse(MinecraftServer server, JsonElement json) {
		return ComponentSerialization.CODEC
			.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
			.getOrThrow(message -> new IllegalArgumentException("Không thể chuyển Adventure component sang Minecraft component: " + message));
	}
}
