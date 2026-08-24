package dev.belikhun.luna.core.forge.network;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hold a stalled player's keepalive deadline open past vanilla's fifteen seconds.
 *
 * 1.20.1 compiles that deadline in as a literal, exactly as 1.12.2 does.
 * {@code ServerGamePacketListenerImpl.tick()} sends a keepalive, and when the next
 * one falls due fifteen seconds later with the first still unanswered it drops the
 * player with {@code disconnect.timeout}. There is no server.properties key for it
 * and no system property: {@code fml.readTimeout} reaches only the netty
 * {@code ReadTimeoutHandler}, which is a separate and much longer deadline measuring
 * *any* inbound traffic, and velocity's own {@code read-timeout} is a third one that
 * was never the kicker. Paper answered this with
 * {@code -Dpaper.playerconnection.keepalive}; forge has nothing, which is why this
 * exists here as well as on the 1.12.2 line.
 *
 * Fifteen seconds is short exactly where a big modpack lives. A client that freezes
 * building chunk meshes, or pauses for a long GC, stops answering, comes back a few
 * seconds later and finds itself disconnected.
 *
 * <p><b>How.</b> Once a tick, a connection with a keepalive outstanding has vanilla's
 * own clock pushed forward so its deadline never arrives - until the stall has run
 * longer than the operator allows, at which point the clock is set *past* the deadline
 * and vanilla drops the player itself on its next check. Nothing here disconnects
 * anybody, and the pending flag and the keepalive challenge are deliberately left
 * alone: clearing either sends a late reply down {@code handleKeepAlive}'s else
 * branch, which disconnects with the very message this exists to prevent.
 *
 * <p><b>The cost is the ping readout.</b> Vanilla derives it from the same clock, so a
 * player who stalls reports the time since the last push rather than since the
 * keepalive was actually sent - one sample that reads too low, against a kick.
 */
public final class KeepaliveExtender {
	/** The deadline compiled into {@code ServerGamePacketListenerImpl.tick()}. */
	private static final long VANILLA_TIMEOUT_MILLIS = 15000L;

	/**
	 * How much of vanilla's window is allowed to run down before the clock is pushed.
	 * A margin means a tick arriving late - and a 300-mod pack does not tick every
	 * 50 ms - still lands before vanilla looks, while pushing on every one of the
	 * twenty ticks a second would rewrite the field for nothing.
	 */
	private static final long PUSH_AFTER_MILLIS = 10000L;

	/**
	 * {@code ServerGamePacketListenerImpl#keepAliveTime}, the moment the outstanding
	 * keepalive was sent, and {@code #keepAlivePending}.
	 *
	 * SRG first, mojmap second. This module compiles against mojmap and the
	 * legacyforge plugin reobfuscates it to SRG on the way into the jar - but a
	 * reflective lookup takes a name as a string, which no remapper rewrites, so a
	 * production server needs {@code f_9747_} and a dev workspace needs
	 * {@code keepAliveTime}. Trying both is what makes one jar work in both places.
	 */
	private static final String[] KEEPALIVE_TIME = { "f_9747_", "keepAliveTime" };

	private static final String[] KEEPALIVE_PENDING = { "f_9748_", "keepAlivePending" };

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final long timeoutMillis;

	/**
	 * When each outstanding keepalive was really sent, which the pushes above would
	 * otherwise erase. Weak keys because a disconnect is the only end of a stall this
	 * never sees.
	 */
	private final Map<ServerGamePacketListenerImpl, Long> stalls = new WeakHashMap<>();

	private Field keepAliveTime;
	private Field keepAlivePending;

	/** Whether the reflective lookup failed; it fails identically every tick. */
	private boolean disabled;

	public KeepaliveExtender(MinecraftServer server, LunaLogger logger, long timeoutMillis) {
		this.server = server;
		this.logger = logger;
		this.timeoutMillis = timeoutMillis;
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (disabled || event.phase != TickEvent.Phase.END) {
			return;
		}

		// vanilla compares against Util.getMillis(), which is System.nanoTime()/1000000,
		// not wall-clock time - and nanoTime's zero is arbitrary, so reading
		// currentTimeMillis() here would compare two unrelated numbers and hold every
		// connection open forever
		long now = System.nanoTime() / 1000000L;

		try {
			resolveFields();

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				ServerGamePacketListenerImpl connection = player.connection;

				if (connection != null) {
					hold(connection, now);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException failure) {
			disabled = true;
			stalls.clear();

			logger.error(
				"Không giữ được kết nối người chơi; timeout keepalive trở về mặc định 15 giây của 1.20.1.",
				failure
			);
		}
	}

	/** Push one connection's deadline, or let go of it once its budget is spent. */
	private void hold(ServerGamePacketListenerImpl connection, long now) throws ReflectiveOperationException {
		if (!keepAlivePending.getBoolean(connection)) {
			stalls.remove(connection);

			return;
		}

		long sentAt = keepAliveTime.getLong(connection);
		Long stalledSince = stalls.get(connection);

		if (stalledSince == null) {
			// first sight of this stall, so the field still holds the real send time
			stalledSince = sentAt;
			stalls.put(connection, stalledSince);
		}

		if (now - stalledSince >= timeoutMillis) {
			// spent: hand the connection back to vanilla already past its deadline, so
			// the disconnect comes from the code that owns it rather than from here.
			// The entry stays, deliberately - dropping it here would let the next tick
			// read the clock we just wrote as a fresh stall and start the budget again,
			// which holds only as long as vanilla is guaranteed to look first
			keepAliveTime.setLong(connection, now - VANILLA_TIMEOUT_MILLIS);

			return;
		}

		if (now - sentAt < PUSH_AFTER_MILLIS) {
			return;
		}

		keepAliveTime.setLong(connection, now);
	}

	/** Resolve both fields once; a miss disables the extender rather than retrying. */
	private void resolveFields() throws ReflectiveOperationException {
		if (keepAliveTime != null) {
			return;
		}

		keepAliveTime = find(KEEPALIVE_TIME);
		keepAlivePending = find(KEEPALIVE_PENDING);
	}

	private static Field find(String... names) throws ReflectiveOperationException {
		for (String name : names) {
			try {
				Field field = ServerGamePacketListenerImpl.class.getDeclaredField(name);

				field.setAccessible(true);

				return field;
			} catch (NoSuchFieldException ignored) {
				// try the next spelling
			}
		}

		throw new NoSuchFieldException(
			"Không tìm thấy field " + names[0] + " trên ServerGamePacketListenerImpl"
				+ "; phiên bản Minecraft/Forge có thể không phải 1.20.1."
		);
	}
}
