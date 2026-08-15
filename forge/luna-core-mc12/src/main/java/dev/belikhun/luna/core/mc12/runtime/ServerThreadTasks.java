package dev.belikhun.luna.core.mc12.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Work for the server thread, without taking vanilla's lock to get there.
 *
 * **`MinecraftServer.addScheduledTask` is not safe to call from a thread that
 * cannot afford to wait.** It takes the monitor on `futureTaskQueue`, and the
 * server thread holds that monitor for the *entire* job-drain phase - which is
 * where inbound packets are processed, because `PacketThreadUtil` enqueues them
 * there. So for as long as any packet handler runs, an off-thread caller trying
 * to hand over work is blocked, with no timeout.
 *
 * That is a deadlock waiting for a reason, and luna supplies one: a handler that
 * blocks on a reply from the proxy. The reply travels on an AMQP consumer whose
 * deliveries are dispatched **serially**, so one delivery hopping to the server
 * thread parks the whole consumer on a monitor held by the very handler waiting
 * for the message behind it. The wait then always expires, whatever its length -
 * which is exactly how it looked: every timeout, at every budget, missed by a
 * couple of milliseconds and succeeded the moment the thread let go.
 *
 * This queue is lock-free, so handing work over never waits, and the tick drains
 * it at both ends of itself.
 */
public final class ServerThreadTasks {
	private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<Runnable>();

	static {
		MinecraftForge.EVENT_BUS.register(new ServerThreadTasks());
	}

	private ServerThreadTasks() {
	}

	/**
	 * Run on the server thread: now if that is already where we are, else next tick.
	 *
	 * The same contract as `addScheduledTask`, which is what callers were written
	 * against; only the way it gets there differs.
	 */
	public static void run(MinecraftServer server, Runnable task) {
		if (task == null) {
			return;
		}

		if (server != null && server.isCallingFromMinecraftThread()) {
			task.run();

			return;
		}

		PENDING.add(task);
	}

	/**
	 * Run on the server thread, but never inside the current call.
	 *
	 * For work that must not happen where it was asked for - opening a window from
	 * inside the click that asked for it, say, which vanilla's own click handling
	 * would then apply to the wrong window.
	 */
	public static void later(Runnable task) {
		if (task != null) {
			PENDING.add(task);
		}
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		Runnable task = PENDING.poll();

		while (task != null) {
			// one failure must not strand the work queued behind it, and must not
			// take the server tick down with it
			try {
				task.run();
			} catch (Throwable failure) {
				LogManager.getLogger("luna").error("Tác vụ luồng máy chủ thất bại.", failure);
			}

			task = PENDING.poll();
		}
	}
}
