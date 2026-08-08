package dev.belikhun.luna.core.messaging.fabric.payload;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One luna plugin message on the wire, as a vanilla custom payload.
 *
 * The bytes are carried opaquely: a channel's own encoding is the business of
 * whoever registered it, and this layer only has to get the block of bytes to
 * the other end intact.
 */
public record PluginPayload(
	CustomPacketPayload.Type<PluginPayload> type,
	PluginMessageChannel channel,
	byte[] data
) implements CustomPacketPayload {
}
