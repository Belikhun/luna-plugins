package dev.belikhun.luna.tv.client.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A plugin message, carried as the bytes the server wrote.
 *
 * One payload class for every channel rather than a record per message: the
 * server writes with DataOutputStream and reads with DataInputStream, so the
 * wire format is already settled and re-describing it here in codecs would only
 * be a second place to get it wrong.
 */
public record TvPayload(Type<TvPayload> id, byte[] bytes) implements CustomPacketPayload {

	/** Client announces itself and its protocol version. */
	public static final Type<TvPayload> HELLO = type("lunatv:hello");

	/** Server describes the screens and how to fetch them. */
	public static final Type<TvPayload> SCREENS = type("lunatv:screens");

	/** Client reports pointer, wheel and keyboard activity. */
	public static final Type<TvPayload> INPUT = type("lunatv:input");

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return id;
	}

	private static Type<TvPayload> type(String channel) {
		return new Type<>(ResourceLocation.parse(channel));
	}

	/**
	 * Builds the codec for one channel.
	 *
	 * @param type the channel's payload type
	 * @return a codec that moves the whole buffer verbatim
	 */
	public static StreamCodec<FriendlyByteBuf, TvPayload> codec(Type<TvPayload> type) {
		return CustomPacketPayload.codec(
			(payload, buffer) -> buffer.writeBytes(payload.bytes()),
			buffer -> {
				byte[] bytes = new byte[buffer.readableBytes()];

				buffer.readBytes(bytes);

				return new TvPayload(type, bytes);
			});
	}
}
