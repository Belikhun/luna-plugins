package dev.belikhun.luna.core.mc12.forwarding;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.forwarding.ForwardedPlayer;
import dev.belikhun.luna.legacy.forwarding.PlayerDataForwarding;
import dev.belikhun.luna.legacy.forwarding.ProtocolBuffer;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;

import java.util.concurrent.TimeUnit;

/**
 * Velocity's modern forwarding, spoken by a 1.12.2 server that has no idea the
 * packets exist.
 *
 * The exchange is a pair of login packets that arrived in 1.13, so vanilla here
 * can neither send nor read them. It does not have to: this handler sits between
 * `splitter` and `decoder`, where every message is one complete packet still in
 * its wire form, and does the whole conversation there. Vanilla's login handler
 * never sees a byte of it - it is simply handed a finished profile at the end.
 *
 * The sequence, all before vanilla processes anything:
 *
 * 1. the handshake goes past untouched; a status ping removes this handler
 * 2. the login start is **withheld**, and a `velocity:player_info` query goes out
 * 3. the proxy's answer is verified against the forwarding secret and read
 * 4. the login handler is given the real profile and told to accept
 *
 * Withholding the login start rather than replaying it is deliberate: vanilla's
 * own handling sets an offline-mode profile and flips straight to the accepting
 * state, and the server thread ticks independently of this one. Overwriting
 * afterwards would be a race whose window is a whole tick.
 */
final class ModernForwardingHandler extends ChannelInboundHandlerAdapter {
	static final String NAME = "luna-forwarding";

	/** Where this handler inserts itself: packets are whole and undecoded there. */
	static final String BEFORE = "decoder";

	/** Outbound writes start here so `prepender` still frames them. */
	private static final String ENCODER = "encoder";

	private static final int HANDSHAKE_PACKET = 0x00;
	private static final int LOGIN_START_PACKET = 0x00;
	private static final int LOGIN_PLUGIN_RESPONSE_PACKET = 0x02;
	private static final int LOGIN_PLUGIN_REQUEST_PACKET = 0x04;

	/** The handshake's `nextState` for a login, as opposed to a status ping. */
	private static final int INTENT_LOGIN = 2;

	/**
	 * How long the proxy has to answer.
	 *
	 * This doubles as the direct-connection message: a player who reaches this
	 * port without going through the proxy will never answer the query, and
	 * without a timeout they would sit on a blank connecting screen rather than
	 * being told what is wrong.
	 */
	private static final long ANSWER_TIMEOUT_SECONDS = 5;

	private enum Phase {
		HANDSHAKE,
		LOGIN_START,
		AWAITING_ANSWER,
		DONE,
	}

	private final String secret;
	private final LunaLogger logger;

	private Phase phase = Phase.HANDSHAKE;
	private int queryId;

	ModernForwardingHandler(String secret, LunaLogger logger) {
		this.secret = secret;
		this.logger = logger;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
		if (phase == Phase.DONE || !(message instanceof ByteBuf)) {
			ctx.fireChannelRead(message);
			return;
		}

		ByteBuf packet = (ByteBuf) message;
		int readerIndex = packet.readerIndex();

		try {
			if (handle(ctx, packet)) {
				// consumed by the exchange; vanilla must not see it
				packet.release();
				return;
			}
		} catch (RuntimeException failure) {
			packet.release();
			refuse(ctx, failure.getMessage());
			return;
		}

		packet.readerIndex(readerIndex);
		ctx.fireChannelRead(packet);
	}

	/** @return whether this packet belonged to the exchange and was consumed */
	private boolean handle(ChannelHandlerContext ctx, ByteBuf packet) {
		ProtocolBuffer buffer = new ProtocolBuffer(bytesOf(packet));
		int id = buffer.readVarInt();

		switch (phase) {
			case HANDSHAKE:
				return handshake(ctx, buffer, id);

			case LOGIN_START:
				if (id == LOGIN_START_PACKET) {
					return sendQuery(ctx, buffer);
				}

				return false;

			case AWAITING_ANSWER:
				if (id == LOGIN_PLUGIN_RESPONSE_PACKET) {
					return readAnswer(ctx, buffer);
				}

				// nothing else is legal here: vanilla is still waiting on a login
				// start it has not been given, so anything arriving now is either a
				// direct connection guessing, or a proxy that answered something else
				return true;

			default:
				return false;
		}
	}

	private boolean handshake(ChannelHandlerContext ctx, ProtocolBuffer buffer, int id) {
		if (id != HANDSHAKE_PACKET) {
			return false;
		}

		buffer.readVarInt();
		buffer.readString();
		buffer.readByte();
		buffer.readByte();

		int intent = buffer.readVarInt();

		if (intent == INTENT_LOGIN) {
			phase = Phase.LOGIN_START;
		} else {
			// a status ping never logs in; stop watching this connection entirely
			remove(ctx);
		}

		return false;
	}

	/**
	 * Withhold the login start and ask the proxy who this is.
	 *
	 * The query is written from the encoder's position in the pipeline, which is
	 * what leaves `prepender` to put the length in front of it - writing from the
	 * tail would hand a raw buffer to vanilla's packet encoder, which expects a
	 * decoded packet and would throw.
	 */
	private boolean sendQuery(ChannelHandlerContext ctx, ProtocolBuffer loginStart) {
		String username = loginStart.readString();

		queryId = ctx.channel().hashCode() & 0x7FFFFFFF;

		byte[] request = new ProtocolBuffer.Writer()
			.writeVarInt(LOGIN_PLUGIN_REQUEST_PACKET)
			.writeVarInt(queryId)
			.writeString(PlayerDataForwarding.CHANNEL)
			.writeBytes(PlayerDataForwarding.request())
			.toBytes();

		phase = Phase.AWAITING_ANSWER;

		ctx.pipeline().context(ENCODER).writeAndFlush(Unpooled.wrappedBuffer(request));

		logger.debug("Đã hỏi proxy danh tính của " + username + " (query " + queryId + ").");

		ctx.executor().schedule(new Runnable() {
			@Override
			public void run() {
				if (phase == Phase.AWAITING_ANSWER) {
					refuse(ctx, "Máy chủ này yêu cầu kết nối qua proxy Velocity.");
				}
			}
		}, ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

		return true;
	}

	private boolean readAnswer(ChannelHandlerContext ctx, ProtocolBuffer buffer) {
		int id = buffer.readVarInt();
		boolean answered = buffer.readBoolean();

		if (id != queryId) {
			throw new IllegalStateException("Proxy trả lời sai query id: " + id + " (chờ " + queryId + ").");
		}

		if (!answered) {
			throw new IllegalStateException(
				"Proxy không hiểu kênh " + PlayerDataForwarding.CHANNEL + "; modern forwarding chưa bật?"
			);
		}

		ForwardedPlayer player = PlayerDataForwarding.verifyAndParse(buffer.readRemaining(), secret);

		NetworkManager connection = (NetworkManager) ctx.pipeline().get("packet_handler");

		if (connection == null || !(connection.getNetHandler() instanceof NetHandlerLoginServer)) {
			throw new IllegalStateException("Không tìm thấy login handler để gán danh tính.");
		}

		NetHandlerLoginServer login = (NetHandlerLoginServer) connection.getNetHandler();

		LoginHandlerAccess.applyRemoteAddress(connection, player, logger);
		LoginHandlerAccess.acceptForwarded(login, player);

		logger.info("Đã xác thực " + player.username() + " (" + player.uniqueId() + ") từ " + player.address() + ".");

		phase = Phase.DONE;
		remove(ctx);

		return true;
	}

	private void refuse(ChannelHandlerContext ctx, String reason) {
		phase = Phase.DONE;

		logger.warn("Từ chối đăng nhập: " + reason);

		NetworkManager connection = (NetworkManager) ctx.pipeline().get("packet_handler");

		if (connection != null) {
			connection.closeChannel(LunaTextComponents.mini("<red>" + reason));
		} else {
			ctx.close();
		}
	}

	private void remove(ChannelHandlerContext ctx) {
		phase = Phase.DONE;

		if (ctx.pipeline().get(NAME) != null) {
			ctx.pipeline().remove(NAME);
		}
	}

	/** A copy, so reading never disturbs the buffer vanilla may still receive. */
	private static byte[] bytesOf(ByteBuf packet) {
		byte[] out = new byte[packet.readableBytes()];

		packet.getBytes(packet.readerIndex(), out);

		return out;
	}
}
