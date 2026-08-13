package dev.belikhun.luna.core.mc12.forwarding;

import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.string.Strings;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;

import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Get {@link ModernForwardingHandler} onto every connection the server accepts.
 *
 * There is no hook for this. Forge's connection events fire during its own
 * handshake, which is long after login, and the login itself is vanilla's. So
 * the listening socket is reached directly: each bound server channel gets a
 * handler that sees every newly accepted child channel, and each child gets an
 * initializer that splices ours in beside vanilla's.
 *
 * The ordering is the fiddly part and is load-bearing. Our acceptor passes the
 * child on **first**, so Minecraft's own initializer is in place before ours is
 * added; both then run in pipeline order at registration, which is what
 * guarantees `decoder` exists to insert in front of. Adding ours first would
 * have it run against an empty pipeline and silently do nothing.
 */
public final class ForwardingInjector {
	private static final String ACCEPTOR = "luna-forwarding-acceptor";

	/** `NetworkSystem#endpoints` */
	private static final String[] ENDPOINTS = { "field_151274_e", "endpoints" };

	private ForwardingInjector() {
	}

	/**
	 * Install forwarding on a running server.
	 *
	 * @param secret the shared forwarding secret; without one nothing is installed,
	 *               because an unverified exchange is worse than none at all
	 * @return whether it was installed
	 */
	public static boolean install(MinecraftServer server, final String secret, final LunaLogger logger) {
		if (Strings.isBlank(secret)) {
			return false;
		}

		List<ChannelFuture> endpoints = endpointsOf(server, logger);

		if (endpoints.isEmpty()) {
			logger.warn("Không tìm thấy endpoint mạng nào; modern forwarding không được cài.");
			return false;
		}

		int installed = 0;

		for (ChannelFuture endpoint : endpoints) {
			Channel channel = endpoint.channel();

			if (channel == null || channel.pipeline().get(ACCEPTOR) != null) {
				continue;
			}

			channel.pipeline().addFirst(ACCEPTOR, new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
					// vanilla's initializer has to be attached before ours
					ctx.fireChannelRead(message);

					if (message instanceof Channel) {
						attach((Channel) message, secret, logger);
					}
				}
			});

			installed += 1;
		}

		return installed > 0;
	}

	private static void attach(Channel child, final String secret, final LunaLogger logger) {
		child.pipeline().addLast(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel channel) {
				if (channel.pipeline().get(ModernForwardingHandler.BEFORE) == null) {
					// a local endpoint (the integrated server) has no packet codec;
					// nothing is proxied to it, so there is nothing to forward
					return;
				}

				channel.pipeline().addBefore(
					ModernForwardingHandler.BEFORE,
					ModernForwardingHandler.NAME,
					new ModernForwardingHandler(secret, logger)
				);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static List<ChannelFuture> endpointsOf(MinecraftServer server, LunaLogger logger) {
		NetworkSystem network = server.getNetworkSystem();

		if (network == null) {
			return new ArrayList<ChannelFuture>();
		}

		Object endpoints = ObfuscatedFields.get(NetworkSystem.class, network, ENDPOINTS);

		if (!(endpoints instanceof List)) {
			logger.warn("Danh sách endpoint có kiểu không mong đợi; bỏ qua modern forwarding.");
			return new ArrayList<ChannelFuture>();
		}

		// the list is synchronized and the server mutates it while binding, so it
		// is copied under its own monitor rather than iterated live
		synchronized (endpoints) {
			return new ArrayList<ChannelFuture>((List<ChannelFuture>) endpoints);
		}
	}
}
