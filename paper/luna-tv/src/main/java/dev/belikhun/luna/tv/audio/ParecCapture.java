package dev.belikhun.luna.tv.audio;

import java.io.IOException;
import java.io.InputStream;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;

/**
 * Records one screen's null sink into 20ms frames.
 *
 * parec is asked for exactly what voice chat wants (48kHz, mono, signed 16-bit
 * little endian), so there is no resampling anywhere in this plugin: bytes come
 * off the pipe, become shorts, and go to the encoder.
 *
 * The reader thread restarts parec on death with a widening delay, and the ring
 * keeps serving silence in the meantime, because a voice-chat audio player that
 * is handed nothing ends its stream permanently.
 */
public final class ParecCapture {

	/** Samples in one voice-chat frame: 20ms at 48kHz. */
	public static final int FRAME_SAMPLES = 960;
	/** Bytes for that frame: mono, two bytes a sample. */
	private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

	private static final long[] RESTART_BACKOFF_MS = { 1_000L, 5_000L, 15_000L, 30_000L };
	private static final int RING_FRAMES = 16;

	private final LunaLogger logger;
	private final TvConfig config;
	private final String sink;
	private final String screenName;
	private final AudioFrameRing ring = new AudioFrameRing(RING_FRAMES, FRAME_SAMPLES);

	private volatile Process process;
	private volatile Thread thread;
	private volatile boolean running;
	private volatile String failure;

	public ParecCapture(LunaLogger logger, TvConfig config, String screenName, String sink) {
		this.logger = logger;
		this.config = config;
		this.screenName = screenName;
		this.sink = sink;
	}

	/** Starts recording. Safe to call twice; the second call does nothing. */
	public void start() {
		if (running) {
			return;
		}

		running = true;
		thread = new Thread(this::pump, "LunaTv-Parec-" + screenName);
		thread.setDaemon(true);
		thread.start();
	}

	/** Stops recording and kills parec. */
	public void stop() {
		running = false;

		Process current = process;

		if (current != null) {
			current.destroyForcibly();
		}

		Thread current_thread = thread;

		if (current_thread != null) {
			current_thread.interrupt();
		}

		thread = null;
		ring.clear();
	}

	/**
	 * Takes the next frame of audio.
	 *
	 * @param into a 960-sample buffer to fill
	 * @return true when real audio was written, false when nothing was buffered
	 *         and the caller should send silence
	 */
	public boolean poll(short[] into) {
		return ring.poll(into);
	}

	/** The last capture failure worth reporting, or null. */
	public String failure() {
		return failure;
	}

	public boolean alive() {
		Process current = process;

		return running && current != null && current.isAlive();
	}

	private void pump() {
		int attempt = 0;

		while (running) {
			try {
				capture();
				// a clean end of stream still means the recorder went away
				attempt = 0;
			} catch (Throwable throwable) {
				if (!running) {
					return;
				}

				failure = String.valueOf(throwable.getMessage());
				logger.warn("parec cho '" + screenName + "' dừng: " + failure);
			}

			if (!running) {
				return;
			}

			long wait = RESTART_BACKOFF_MS[Math.min(attempt, RESTART_BACKOFF_MS.length - 1)];
			attempt++;

			try {
				Thread.sleep(wait);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();

				return;
			}
		}
	}

	private void capture() throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(
			config.parecPath(),
			"--device=" + sink + ".monitor",
			"--format=s16le",
			"--rate=48000",
			"--channels=1",
			"--latency-msec=40",
			"--client-name=LunaTV-" + screenName);

		builder.redirectError(ProcessBuilder.Redirect.DISCARD);

		Process started = builder.start();
		process = started;

		byte[] bytes = new byte[FRAME_BYTES];
		short[] frame = new short[FRAME_SAMPLES];

		try (InputStream stream = started.getInputStream()) {
			while (running) {
				if (!readFully(stream, bytes)) {
					break;
				}

				toShorts(bytes, frame);
				ring.push(frame);
			}
		} finally {
			started.destroyForcibly();
			started.waitFor();
			process = null;
		}
	}

	/**
	 * Reads a whole frame, because a pipe read returns whatever is ready and a
	 * half frame would shift every following sample by a byte.
	 */
	private static boolean readFully(InputStream stream, byte[] into) throws IOException {
		int offset = 0;

		while (offset < into.length) {
			int read = stream.read(into, offset, into.length - offset);

			if (read < 0) {
				return false;
			}

			offset += read;
		}

		return true;
	}

	private static void toShorts(byte[] bytes, short[] into) {
		for (int index = 0; index < into.length; index++) {
			int low = bytes[index * 2] & 0xFF;
			int high = bytes[index * 2 + 1];

			into[index] = (short) ((high << 8) | low);
		}
	}
}
