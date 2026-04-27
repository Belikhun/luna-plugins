package dev.belikhun.luna.core.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

public final class NeoForgeTextComponents {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final GsonComponentSerializer GSON_COMPONENT_SERIALIZER = GsonComponentSerializer.gson();

	private NeoForgeTextComponents() {
	}

	public static net.minecraft.network.chat.Component mini(MinecraftServer server, String miniMessage) {
		String normalized = miniMessage == null ? "" : miniMessage;
		if (normalized.isBlank()) {
			return net.minecraft.network.chat.Component.empty();
		}

		return adventure(server, MINI_MESSAGE.deserialize("<!italic>" + normalized));
	}

	public static net.minecraft.network.chat.Component adventure(MinecraftServer server, Component component) {
		if (server == null || component == null) {
			return net.minecraft.network.chat.Component.empty();
		}

		String json = GSON_COMPONENT_SERIALIZER.serialize(component);
		JsonElement jsonElement = GSON.fromJson(json, JsonElement.class);
		return ComponentSerialization.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), jsonElement)
			.getOrThrow(message -> new IllegalArgumentException("Không thể chuyển Adventure component sang Minecraft component: " + message));
	}
}
