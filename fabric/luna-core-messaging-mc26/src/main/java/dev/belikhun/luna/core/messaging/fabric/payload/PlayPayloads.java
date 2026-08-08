package dev.belikhun.luna.core.messaging.fabric.payload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Naming and registering a play-phase payload type, for Minecraft 26.1 and up.
 *
 * The 1.21 module's copy of this class is the same two calls under the names
 * that line still uses; see it for why this is a separate file rather than a
 * version test.
 */
final class PlayPayloads {
	private PlayPayloads() {
	}

	static CustomPacketPayload.Type<PluginPayload> type(String channelValue) {
		return new CustomPacketPayload.Type<>(Identifier.parse(channelValue));
	}

	static void register(
		CustomPacketPayload.Type<PluginPayload> type,
		StreamCodec<RegistryFriendlyByteBuf, PluginPayload> codec
	) {
		PayloadTypeRegistry.serverboundPlay().register(type, codec);
		PayloadTypeRegistry.clientboundPlay().register(type, codec);
	}
}
