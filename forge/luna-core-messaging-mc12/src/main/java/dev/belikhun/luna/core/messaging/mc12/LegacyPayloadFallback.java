package dev.belikhun.luna.core.messaging.mc12;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;
import dev.belikhun.luna.legacy.messaging.bus.PayloadFallback;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Carrying a plugin message on the player's own connection, on 1.12.2.
 *
 * The bus is AMQP-first on this line and that is right for luna's own channels:
 * every one of them is `luna:`-namespaced past the 20 characters this protocol
 * allows a channel name, so a custom payload cannot carry them at all.
 *
 * **A third party's channel is a different question.** TAB's `tab:bridge-6` is
 * twelve characters, and TAB's proxy half listens for plugin messages - it knows
 * nothing about luna's broker, so a bridge that published to AMQP would be
 * talking to nobody. Those channels have to ride the connection, and this is what
 * carries them.
 *
 * 1.12.2 has no packet-payload registry, so the mechanism is FML's event-driven
 * channel: one per channel name, constructed while a mod container is active,
 * dispatching on the netty thread. Everything inbound is therefore handed to the
 * server thread before it reaches a listener, because a listener resolves
 * placeholders and reads the world.
 */
public final class LegacyPayloadFallback implements PayloadFallback<EntityPlayerMP> {
	private final LunaLogger logger;
	private final PlayerBridge<EntityPlayerMP> players;
	private final Set<PluginMessageChannel> channels;
	private final FMLEventChannel channel;
	private final String channelName;

	private volatile InboundSink<EntityPlayerMP> sink;

	/**
	 * @param carried the channels this fallback should claim; every one must fit
	 *                1.12.2's 20-character limit or FML will register a name the
	 *                proxy can never match
	 */
	public LegacyPayloadFallback(
		LunaLogger logger,
		PlayerBridge<EntityPlayerMP> players,
		PluginMessageChannel carried
	) {
		this.logger = logger.scope("PayloadFallback");
		this.players = players;
		this.channelName = carried.value();
		this.channels = Collections.unmodifiableSet(
			new LinkedHashSet<PluginMessageChannel>(Collections.singletonList(carried))
		);

		if (channelName.length() > MAX_CHANNEL_NAME_LENGTH) {
			throw new IllegalArgumentException(
				"Channel vượt quá giới hạn 20 ký tự của 1.12.2: " + channelName
			);
		}

		this.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(channelName);
		this.channel.register(this);
	}

	/** 1.12.2's protocol limit on a plugin channel name. */
	private static final int MAX_CHANNEL_NAME_LENGTH = 20;

	@Override
	public void attach(InboundSink<EntityPlayerMP> sink) {
		this.sink = sink;
	}

	@Override
	public void detach() {
		this.sink = null;
		channel.unregister(this);
	}

	@Override
	public boolean supports(PluginMessageChannel channel) {
		return channel != null && channels.contains(channel);
	}

	@Override
	public boolean send(EntityPlayerMP target, PluginMessageChannel channel, byte[] payload) {
		if (target == null || !supports(channel) || payload == null) {
			return false;
		}

		try {
			ByteBuf buffer = Unpooled.wrappedBuffer(payload);

			this.channel.sendTo(new FMLProxyPacket(new PacketBuffer(buffer), channelName), target);

			return true;
		} catch (RuntimeException exception) {
			logger.debug("Không gửi được custom payload trên " + channelName
				+ " cho " + players.nameOf(target) + ": " + exception.getMessage());

			return false;
		}
	}

	/**
	 * A payload arriving from the proxy, on the netty thread.
	 *
	 * The bytes are copied out here rather than passed as a `ByteBuf`: netty
	 * recycles the buffer as soon as this returns, and the sink runs a tick later.
	 */
	@SubscribeEvent
	public void onServerCustomPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
		final InboundSink<EntityPlayerMP> currentSink = sink;

		if (currentSink == null) {
			return;
		}

		final EntityPlayerMP sender = senderOf(event);
		final byte[] payload = copyPayload(event.getPacket());

		if (sender == null || payload == null) {
			return;
		}

		players.onServerThread(new Runnable() {
			@Override
			public void run() {
				try {
					currentSink.accept(sender, channelFor(event.getPacket().channel()), payload);
				} catch (RuntimeException failure) {
					logger.warn("Lỗi khi xử lý custom payload trên " + channelName + ": " + failure.getMessage());
				}
			}
		});
	}

	private PluginMessageChannel channelFor(String name) {
		return PluginMessageChannel.of(name);
	}

	private byte[] copyPayload(FMLProxyPacket packet) {
		if (packet == null) {
			return null;
		}

		ByteBuf buffer = packet.payload();

		if (buffer == null) {
			return null;
		}

		byte[] payload = new byte[buffer.readableBytes()];

		buffer.getBytes(buffer.readerIndex(), payload);

		return payload;
	}

	private EntityPlayerMP senderOf(FMLNetworkEvent.ServerCustomPacketEvent event) {
		if (!(event.getHandler() instanceof NetHandlerPlayServer)) {
			return null;
		}

		return ((NetHandlerPlayServer) event.getHandler()).player;
	}
}
