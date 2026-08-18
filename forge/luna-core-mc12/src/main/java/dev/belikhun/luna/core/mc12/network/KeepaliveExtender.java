package dev.belikhun.luna.core.mc12.network;

import dev.belikhun.luna.core.mc12.reflect.ObfuscatedFields;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hold a stalled player's keepalive deadline open past vanilla's fifteen seconds.
 *
 * 1.12.2 compiles that deadline in as a literal. `NetHandlerPlayServer.update()`
 * sends a keepalive, and when the next one falls due fifteen seconds later with the
 * first still unanswered it drops the player with `disconnect.timeout`. There is no
 * server.properties key for it and no system property: `fml.readTimeout` reaches
 * only the netty `ReadTimeoutHandler`, which is a separate and much longer deadline
 * measuring *any* inbound traffic. Paper answered this with
 * `-Dpaper.playerconnection.keepalive`; below 1.13 the only place left to answer it
 * is inside the server, which is why this exists.
 *
 * Fifteen seconds is short exactly where a big modpack lives. A client that freezes
 * building chunk meshes, or pauses for a long GC, stops answering, comes back a few
 * seconds later and finds itself disconnected.
 *
 * **How.** Once a tick, a connection with a keepalive outstanding has vanilla's own
 * clock pushed forward so its deadline never arrives - until the stall has run
 * longer than the operator allows, at which point the clock is set *past* the
 * deadline and vanilla drops the player itself on its next check. Nothing here
 * disconnects anybody, and the pending flag and the keepalive id are deliberately
 * left alone: clearing either sends a late reply down `processKeepAlive`'s else
 * branch, which disconnects with the very message this exists to prevent.
 *
 * **The cost is the ping readout.** Vanilla derives it from the same clock, so a
 * player who stalls reports the time since the last push rather than since the
 * keepalive was actually sent - one sample that reads too low, against a kick.
 */
public final class KeepaliveExtender {
	/** The deadline compiled into `NetHandlerPlayServer.update()`. */
	private static final long VANILLA_TIMEOUT_MILLIS = 15000L;

	/**
	 * How much of vanilla's window is allowed to run down before the clock is
	 * pushed. A margin means a tick arriving late - and a 6 GB modpack does not tick
	 * every 50 ms - still lands before vanilla looks, while pushing on every one of
	 * the twenty ticks a second would rewrite the field for nothing.
	 */
	private static final long PUSH_AFTER_MILLIS = 10000L;

	/**
	 * `NetHandlerPlayServer#keepAliveTime`, the moment the outstanding keepalive was
	 * sent, and `#keepAlivePending`. One spelling each: MCP 1.12.2 never named
	 * either, so SRG and MCP agree and there is no second name to try.
	 */
	private static final String[] KEEPALIVE_TIME = { "field_194402_f" };

	private static final String[] KEEPALIVE_PENDING = { "field_194403_g" };

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final long timeoutMillis;

	/**
	 * When each outstanding keepalive was really sent, which the pushes above would
	 * otherwise erase. Weak keys because a disconnect is the only end of a stall
	 * this never sees.
	 */
	private final Map<NetHandlerPlayServer, Long> stalls = new WeakHashMap<NetHandlerPlayServer, Long>();

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

		// vanilla compares against System.nanoTime()/1000000, not wall-clock time, and
		// nanoTime's zero is arbitrary - reading currentTimeMillis() here would compare
		// two unrelated numbers and hold every connection open forever
		long now = System.nanoTime() / 1000000L;

		try {
			for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
				NetHandlerPlayServer connection = player.connection;

				if (connection != null) {
					hold(connection, now);
				}
			}
		} catch (RuntimeException failure) {
			disabled = true;
			stalls.clear();

			logger.error(
				"Không giữ được kết nối người chơi; timeout keepalive trở về mặc định 15 giây của 1.12.2.",
				failure
			);
		}
	}

	/** Push one connection's deadline, or let go of it once its budget is spent. */
	private void hold(NetHandlerPlayServer connection, long now) {
		boolean pending = (Boolean) ObfuscatedFields.get(
			NetHandlerPlayServer.class,
			connection,
			KEEPALIVE_PENDING
		);

		if (!pending) {
			stalls.remove(connection);

			return;
		}

		long sentAt = (Long) ObfuscatedFields.get(NetHandlerPlayServer.class, connection, KEEPALIVE_TIME);
		Long stalledSince = stalls.get(connection);

		if (stalledSince == null) {
			// first sight of this stall, so the field still holds the real send time
			stalledSince = Long.valueOf(sentAt);
			stalls.put(connection, stalledSince);
		}

		if (now - stalledSince.longValue() >= timeoutMillis) {
			// spent: hand the connection back to vanilla already past its deadline, so
			// the disconnect comes from the code that owns it rather than from here.
			// The entry stays, deliberately - dropping it here would let the next tick
			// read the clock we just wrote as a fresh stall and start the budget again,
			// which holds only as long as vanilla is guaranteed to look first
			ObfuscatedFields.set(
				NetHandlerPlayServer.class,
				connection,
				Long.valueOf(now - VANILLA_TIMEOUT_MILLIS),
				KEEPALIVE_TIME
			);

			return;
		}

		if (now - sentAt < PUSH_AFTER_MILLIS) {
			return;
		}

		ObfuscatedFields.set(NetHandlerPlayServer.class, connection, Long.valueOf(now), KEEPALIVE_TIME);
	}
}
