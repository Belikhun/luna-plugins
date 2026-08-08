package dev.belikhun.luna.core.messaging.fabric.payload;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelCatalogue;
import dev.belikhun.luna.core.api.messaging.PluginMessageTransportType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Plugin messages over the player's own connection, which is how they reach
 * velocity.
 *
 * Vanilla drops a custom payload whose channel it does not know, so both
 * directions have to be declared during mod initialisation, before any player
 * connects; registration is therefore static and happens once. Which bus the
 * arriving bytes are handed to is a separate question, answered by
 * {@link #activate}, because the bus only exists once a server is running.
 */
public final class PayloadFallbackTransport {
	/** A channel nobody would legitimately fill; beyond this the sender is confused. */
	private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

	private static final Map<PluginMessageChannel, RegisteredChannel> REGISTERED_CHANNELS = createRegisteredChannels();

	private static volatile Object activeOwner;
	private static volatile BiConsumer<ServerPlayer, PluginPayload> activeSink;

	private PayloadFallbackTransport() {
	}

	/**
	 * Declare every fallback channel with the game's packet codecs. Call from the
	 * mod initialiser: after a player has joined it is already too late.
	 *
	 * @return the channels that were declared; empty means no provider was found,
	 *         which leaves the connection route dead and everything on the broker
	 */
	public static Set<PluginMessageChannel> registerPayloadTypes() {
		for (RegisteredChannel channel : REGISTERED_CHANNELS.values()) {
			PlayPayloads.register(channel.type, channel.codec);
			ServerPlayNetworking.registerGlobalReceiver(channel.type, (payload, context) -> receive(payload, context.player()));
		}

		return REGISTERED_CHANNELS.keySet();
	}

	/** Route arriving messages to this bus until it is deactivated. */
	public static void activate(Object owner, BiConsumer<ServerPlayer, PluginPayload> sink) {
		activeOwner = owner;
		activeSink = sink;
	}

	/** Stop routing, if this owner is the one currently routing. */
	public static void deactivate(Object owner) {
		if (activeOwner == owner) {
			activeOwner = null;
			activeSink = null;
		}
	}

	/** Whether this channel travels over the player connection at all. */
	public static boolean supports(PluginMessageChannel channel) {
		return channel != null && REGISTERED_CHANNELS.containsKey(channel);
	}

	/** Send one message; false when the channel is not a fallback channel. */
	public static boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		if (target == null || channel == null || payload == null) {
			return false;
		}

		RegisteredChannel registeredChannel = REGISTERED_CHANNELS.get(channel);

		if (registeredChannel == null) {
			return false;
		}

		ServerPlayNetworking.send(target, registeredChannel.create(payload));

		return true;
	}

	private static void receive(PluginPayload payload, ServerPlayer sender) {
		BiConsumer<ServerPlayer, PluginPayload> sink = activeSink;

		if (sink == null) {
			return;
		}

		sink.accept(sender, payload);
	}

	private static Map<PluginMessageChannel, RegisteredChannel> createRegisteredChannels() {
		Map<PluginMessageChannel, RegisteredChannel> values = new LinkedHashMap<>();

		for (PluginMessageChannel channel : PluginMessageChannelCatalogue.channelsFor(PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK)) {
			values.put(channel, new RegisteredChannel(channel));
		}

		return Map.copyOf(values);
	}

	private static final class RegisteredChannel {
		private final PluginMessageChannel channel;
		private final CustomPacketPayload.Type<PluginPayload> type;
		private final StreamCodec<RegistryFriendlyByteBuf, PluginPayload> codec;

		private RegisteredChannel(PluginMessageChannel channel) {
			this.channel = channel;
			this.type = PlayPayloads.type(channel.value());
			this.codec = new StreamCodec<>() {
				@Override
				public PluginPayload decode(RegistryFriendlyByteBuf buf) {
					int readableBytes = buf.readableBytes();

					if (readableBytes > MAX_PAYLOAD_BYTES) {
						throw new IllegalArgumentException("Plugin payload quá lớn cho channel " + RegisteredChannel.this.channel.value());
					}

					byte[] data = new byte[readableBytes];
					buf.readBytes(data);

					return new PluginPayload(type, RegisteredChannel.this.channel, data);
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, PluginPayload payload) {
					buf.writeBytes(payload.data());
				}
			};
		}

		private PluginPayload create(byte[] payload) {
			return new PluginPayload(type, channel, payload);
		}
	}
}
