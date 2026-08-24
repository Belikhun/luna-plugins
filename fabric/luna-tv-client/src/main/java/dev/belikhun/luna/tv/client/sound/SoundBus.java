package dev.belikhun.luna.tv.client.sound;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thread that talks to OpenAL on this mod's behalf.
 *
 * Every screen's sound is pumped from here rather than from the client tick.
 * A tick is 50ms apart at best and further apart whenever a chunk loads, so
 * feeding OpenAL from it would need a quarter of a second of buffer to survive
 * an ordinary stutter, and a quarter of a second is exactly the lip-sync error
 * a talking head makes obvious. Ten milliseconds of grain costs nothing and
 * keeps the buffer at the size the network actually needs.
 *
 * The client tick still owns everything to do with the world: it takes the
 * camera's basis and the player's volume setting once per tick and leaves them
 * here, and this thread reads that snapshot. Nothing here touches Minecraft.
 */
public final class SoundBus {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** How often the mixer looks at every screen. */
	private static final long PUMP_NANOS = 10_000_000L;

	private final CopyOnWriteArrayList<ScreenSound> sounds = new CopyOnWriteArrayList<>();

	/** Camera position, then its left, up and forward unit vectors. */
	private volatile double[] listener;

	private volatile float master = 1.0f;
	private volatile Thread thread;
	private volatile boolean running;

	/**
	 * Starts the mixer, if it is not already running.
	 *
	 * Synchronised with {@link #stop()} and waits for a previous mixer to finish
	 * its closing pass: rejoining a server the moment after leaving one must not
	 * leave two threads sharing a source list, because the one that is shutting
	 * down would delete sources the other is still queueing into.
	 */
	public synchronized void start() {
		if (running) {
			return;
		}

		Thread previous = thread;

		if (previous != null) {
			try {
				previous.join(500L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		running = true;
		thread = new Thread(this::pump, "LunaTv-Sound");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Hands the mixer this tick's view of the listener.
	 *
	 * @param listener camera position, then its left, up and forward vectors
	 * @param master the player's own volume for this kind of sound, 0 to 1
	 */
	public void listener(double[] listener, float master) {
		this.listener = listener;
		this.master = master;
	}

	/**
	 * Adds a screen's sound and starts fetching it.
	 *
	 * @param sound the sound to mix from now on
	 */
	public void add(ScreenSound sound) {
		sound.start();
		sounds.add(sound);
		start();
	}

	/**
	 * Stops a screen's sound.
	 *
	 * The mixer thread is what actually deletes its OpenAL objects, on its next
	 * pass, because every call this mod makes to OpenAL is made from there.
	 *
	 * @param sound the sound to drop
	 */
	public void remove(ScreenSound sound) {
		sound.close();
	}

	/** Stops every sound and the mixer with them. */
	public synchronized void stop() {
		for (ScreenSound sound : sounds) {
			sound.close();
		}

		running = false;

		Thread current = thread;

		if (current != null) {
			LockSupport.unpark(current);
		}
	}

	private void pump() {
		while (running) {
			double[] basis = listener;
			float level = master;

			for (ScreenSound sound : sounds) {
				step(sound, basis, level);
			}

			LockSupport.parkNanos(PUMP_NANOS);
		}

		// the closing pass: every sound was told to close before running went
		// false, so one more turn is all it takes to free their sources
		for (ScreenSound sound : sounds) {
			step(sound, null, 0.0f);
		}

		sounds.clear();
	}

	private void step(ScreenSound sound, double[] basis, float level) {
		try {
			if (!sound.pump(basis, level)) {
				sounds.remove(sound);
			}
		} catch (Throwable throwable) {
			LOGGER.warn("Luna TV sound failed, dropping it: {}", throwable.toString());
			sound.close();
			sounds.remove(sound);
		}
	}
}
