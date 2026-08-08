package dev.belikhun.luna.core.messaging.fabric.payload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Naming and registering a play-phase payload type, for Minecraft 1.20.5 through
 * 1.21.x.
 *
 * Two things moved in the 26.x line and neither can be absorbed at runtime:
 * {@code ResourceLocation} was renamed to {@code Identifier}, and fabric-api
 * renamed the play registries (playC2S/playS2C became serverboundPlay/
 * clientboundPlay). The sibling module supplies its own copy of this class, and
 * nothing else in the module has to know which line it is on.
 *
 * Note that the game's own {@code CustomPacketPayload.createType} is not usable
 * here: it treats its whole argument as a path under the {@code minecraft}
 * namespace, and a luna channel carries its own.
 */
final class PlayPayloads {
	private PlayPayloads() {
	}

	static CustomPacketPayload.Type<PluginPayload> type(String channelValue) {
		return new CustomPacketPayload.Type<>(ResourceLocation.parse(channelValue));
	}

	static void register(
		CustomPacketPayload.Type<PluginPayload> type,
		StreamCodec<RegistryFriendlyByteBuf, PluginPayload> codec
	) {
		PayloadTypeRegistry.playC2S().register(type, codec);
		PayloadTypeRegistry.playS2C().register(type, codec);
	}
}
