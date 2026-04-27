package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelCatalogue;
import dev.belikhun.luna.core.api.messaging.PluginMessageTransportType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.LinkedHashMap;
import java.util.Map;

final class NeoForgePayloadFallbackTransport {
	private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
	private static final Map<PluginMessageChannel, RegisteredChannel> REGISTERED_CHANNELS = createRegisteredChannels();

	private static volatile NeoForgePluginMessagingBus activeBus;

	private NeoForgePayloadFallbackTransport() {
	}

	static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1").optional();
		for (RegisteredChannel channel : REGISTERED_CHANNELS.values()) {
			registrar.playBidirectional(channel.type(), channel.codec(), NeoForgePayloadFallbackTransport::handleServerbound);
		}
	}

	static void activate(NeoForgePluginMessagingBus bus) {
		activeBus = bus;
	}

	static void deactivate(NeoForgePluginMessagingBus bus) {
		if (activeBus == bus) {
			activeBus = null;
		}
	}

	static boolean supports(PluginMessageChannel channel) {
		return channel != null && REGISTERED_CHANNELS.containsKey(channel);
	}

	static boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		if (target == null || channel == null || payload == null) {
			return false;
		}

		RegisteredChannel registeredChannel = REGISTERED_CHANNELS.get(channel);
		if (registeredChannel == null) {
			return false;
		}

		target.connection.send(new ClientboundCustomPayloadPacket(registeredChannel.create(payload)));
		return true;
	}

	private static void handleServerbound(RegisteredPluginPayload payload, IPayloadContext context) {
		NeoForgePluginMessagingBus bus = activeBus;
		if (bus == null) {
			return;
		}

		ServerPlayer sender = context.player() instanceof ServerPlayer player ? player : null;
		bus.dispatchIncoming(sender, payload.channel(), payload.data());
	}

	private static Map<PluginMessageChannel, RegisteredChannel> createRegisteredChannels() {
		Map<PluginMessageChannel, RegisteredChannel> values = new LinkedHashMap<>();
		for (PluginMessageChannel channel : PluginMessageChannelCatalogue.channelsFor(PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK)) {
			values.put(channel, new RegisteredChannel(channel));
		}
		return Map.copyOf(values);
	}

	private static ResourceLocation identifier(String channelValue) {
		int separator = channelValue.indexOf(':');
		if (separator <= 0 || separator >= channelValue.length() - 1) {
			throw new IllegalArgumentException("Channel không hợp lệ cho NeoForge payload fallback: " + channelValue);
		}

		return ResourceLocation.fromNamespaceAndPath(
			channelValue.substring(0, separator),
			channelValue.substring(separator + 1)
		);
	}

	private static final class RegisteredChannel {
		private final PluginMessageChannel channel;
		private final CustomPacketPayload.Type<RegisteredPluginPayload> type;
		private final StreamCodec<RegistryFriendlyByteBuf, RegisteredPluginPayload> codec;

		private RegisteredChannel(PluginMessageChannel channel) {
			this.channel = channel;
			this.type = new CustomPacketPayload.Type<>(identifier(channel.value()));
			this.codec = new StreamCodec<>() {
				@Override
				public RegisteredPluginPayload decode(RegistryFriendlyByteBuf buf) {
					int readableBytes = buf.readableBytes();
					if (readableBytes > MAX_PAYLOAD_BYTES) {
						throw new IllegalArgumentException("Plugin payload quá lớn cho channel " + RegisteredChannel.this.channel.value());
					}

					byte[] data = new byte[readableBytes];
					buf.readBytes(data);
					return new RegisteredPluginPayload(type, RegisteredChannel.this.channel, data);
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, RegisteredPluginPayload payload) {
					buf.writeBytes(payload.data());
				}
			};
		}

		private PluginMessageChannel channel() {
			return channel;
		}

		private CustomPacketPayload.Type<RegisteredPluginPayload> type() {
			return type;
		}

		private StreamCodec<RegistryFriendlyByteBuf, RegisteredPluginPayload> codec() {
			return codec;
		}

		private RegisteredPluginPayload create(byte[] payload) {
			return new RegisteredPluginPayload(type, channel, payload);
		}
	}

	private record RegisteredPluginPayload(
		CustomPacketPayload.Type<RegisteredPluginPayload> type,
		PluginMessageChannel channel,
		byte[] data
	) implements CustomPacketPayload {
	}
}
