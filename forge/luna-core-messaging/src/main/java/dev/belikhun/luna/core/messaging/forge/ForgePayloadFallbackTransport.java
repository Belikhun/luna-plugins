package dev.belikhun.luna.core.messaging.forge;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelCatalogue;
import dev.belikhun.luna.core.api.messaging.PluginMessageTransportType;
import dev.belikhun.luna.core.messaging.mc.PayloadFallback;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carrying luna's plugin messages on the player's own connection, for forge.
 *
 * Deliberately not a {@code SimpleChannel}: that is forge's own protocol and it
 * prefixes every message with a discriminator, which the velocity side does not
 * speak. What the proxy expects is a plain vanilla custom payload - the channel
 * name and the bytes, nothing around them - so this sends the vanilla packet
 * directly and takes an {@link EventNetworkChannel} for the inbound half, that
 * being forge's raw-channel API and the one place it does not add a wrapper.
 *
 * The 1.21 loaders register a typed payload instead; see the neoforge transport.
 */
public final class ForgePayloadFallbackTransport implements PayloadFallback {
	private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

	/** Every version is acceptable: this channel carries luna's own framing, not forge's. */
	private static final String CHANNEL_VERSION = "1";

	private final Map<PluginMessageChannel, ResourceLocation> identifiers = new LinkedHashMap<>();
	private final Map<PluginMessageChannel, EventNetworkChannel> channels = new LinkedHashMap<>();

	private volatile InboundSink sink;

	/**
	 * Register a raw channel per fallback-capable channel.
	 *
	 * Registration happens once, at mod construction, because forge builds its
	 * channel list before the server exists; the sink arrives later.
	 */
	public ForgePayloadFallbackTransport() {
		for (PluginMessageChannel channel : PluginMessageChannelCatalogue.channelsFor(PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK)) {
			ResourceLocation identifier = identifier(channel.value());
			EventNetworkChannel networkChannel = NetworkRegistry.newEventChannel(
				identifier,
				() -> CHANNEL_VERSION,
				version -> true,
				version -> true
			);

			networkChannel.addListener(event -> {
				// this line's NetworkEvent hands out its context through a supplier
				NetworkEvent.Context context = event.getSource().get();
				InboundSink target = sink;

				if (target == null) {
					context.setPacketHandled(true);
					return;
				}

				ServerPlayer sender = context.getSender();
				FriendlyByteBuf buffer = event.getPayload();
				byte[] payload = buffer == null ? new byte[0] : readAll(buffer);

				target.accept(sender, channel, payload);
				context.setPacketHandled(true);
			});

			identifiers.put(channel, identifier);
			channels.put(channel, networkChannel);
		}
	}

	@Override
	public void attach(InboundSink sink) {
		this.sink = sink;
	}

	@Override
	public void detach() {
		this.sink = null;
	}

	@Override
	public boolean supports(PluginMessageChannel channel) {
		return channel != null && identifiers.containsKey(channel);
	}

	@Override
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		if (target == null || channel == null || payload == null) {
			return false;
		}

		ResourceLocation identifier = identifiers.get(channel);

		if (identifier == null || payload.length > MAX_PAYLOAD_BYTES) {
			return false;
		}

		target.connection.send(new ClientboundCustomPayloadPacket(
			identifier,
			new FriendlyByteBuf(Unpooled.wrappedBuffer(payload))
		));

		return true;
	}

	private static byte[] readAll(FriendlyByteBuf buffer) {
		byte[] bytes = new byte[buffer.readableBytes()];
		buffer.readBytes(bytes);

		return bytes;
	}

	private static ResourceLocation identifier(String channelValue) {
		int separator = channelValue.indexOf(':');

		if (separator <= 0 || separator >= channelValue.length() - 1) {
			throw new IllegalArgumentException("Channel không hợp lệ cho Forge payload fallback: " + channelValue);
		}

		return new ResourceLocation(
			channelValue.substring(0, separator),
			channelValue.substring(separator + 1)
		);
	}
}
