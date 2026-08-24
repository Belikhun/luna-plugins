package dev.belikhun.luna.tv.client.sound;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One screen's sound, fetched on its own thread.
 *
 * The stream is raw 48kHz signed 16-bit PCM, which is deliberate on both ends:
 * the server has it in that form already because that is what voice chat wanted,
 * and a client that needs no decoder needs no native library either. Twenty
 * milliseconds of mono is 1920 bytes, so a screen costs about 770 kbit/s of the
 * same connection its picture arrives on, which is a rounding error beside the
 * picture.
 *
 * Every block is framed with a magic word, its length and the moment it was
 * captured. The length is what makes the framing self-describing, and the magic
 * is what turns a desynchronised reader into an error instead of noise.
 *
 * Unlike the picture, sound is never newest-wins. A frame that is replaced
 * before it is drawn costs nothing; a block of audio that is skipped is an
 * audible click, so blocks queue in order and only a listener that has fallen a
 * second behind loses any.
 */
public final class AudioFeed implements AutoCloseable {

	/** In front of every block, so lost framing is noticed rather than played. */
	private static final int MAGIC = 0x4C545641;

	/** Nothing legitimate reaches this; past it the framing has been lost. */
	private static final int MAX_BLOCK_BYTES = 1 << 20;

	/** Blocks allowed to pile up before the oldest are dropped: about a second. */
	private static final int MAX_QUEUED = 50;

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** Waits between reconnects, widening so a dead server is not hammered. */
	private static final long[] RETRY_BACKOFF_MS = { 1_000L, 3_000L, 10_000L, 30_000L };

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	private final String name;

	/** Built per attempt: the token inside dies with the server that issued it. */
	private final Supplier<String> url;

	private final ConcurrentLinkedQueue<short[]> blocks = new ConcurrentLinkedQueue<>();
	private final AtomicInteger depth = new AtomicInteger();

	private volatile Thread thread;
	private volatile boolean running;
	private volatile String failure;

	public AudioFeed(String name, Supplier<String> url) {
		this.name = name;
		this.url = url;
	}

	/** Starts fetching. */
	public void start() {
		if (running) {
			return;
		}

		running = true;
		thread = new Thread(this::pump, "LunaTv-Audio-" + name);
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Takes the oldest block still waiting.
	 *
	 * @return 48kHz samples, interleaved when the screen is stereo, or null
	 */
	public short[] poll() {
		short[] block = blocks.poll();

		if (block != null) {
			depth.decrementAndGet();
		}

		return block;
	}

	/** How many blocks are waiting to be played. */
	public int depth() {
		return depth.get();
	}

	/**
	 * Throws away everything past the given depth, oldest first.
	 *
	 * The playback side calls this when it cannot take any more: a backlog it
	 * will eventually get to is not a saving, it is latency, and audio that
	 * arrives a second after its picture is worse than audio with a gap in it.
	 *
	 * @param keep how many blocks may stay
	 */
	public void trim(int keep) {
		while (depth.get() > keep) {
			if (blocks.poll() == null) {
				return;
			}

			depth.decrementAndGet();
		}
	}

	/** Why the feed is unhappy, or null. */
	public String failure() {
		return failure;
	}

	@Override
	public void close() {
		running = false;

		Thread current = thread;

		if (current != null) {
			current.interrupt();
		}

		thread = null;
		blocks.clear();
		depth.set(0);
	}

	private void pump() {
		int attempt = 0;

		while (running) {
			try {
				read();
				attempt = 0;
			} catch (Throwable throwable) {
				if (!running) {
					return;
				}

				failure = String.valueOf(throwable.getMessage());
				LOGGER.warn("Luna TV audio {} failed: {}", name, throwable.toString());
			}

			if (!running) {
				return;
			}

			try {
				Thread.sleep(RETRY_BACKOFF_MS[Math.min(attempt, RETRY_BACKOFF_MS.length - 1)]);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();

				return;
			}

			attempt++;
		}
	}

	private void read() throws IOException, InterruptedException {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

		HttpRequest request = HttpRequest.newBuilder(URI.create(url.get()))
			.header("Accept", "audio/L16")
			.GET()
			.build();

		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

		// 409 is the server saying the screen has no capture running yet, which
		// happens whenever sound is switched on a moment after the picture. It is
		// a wait, not a fault, so it retries on the same backoff as everything else.
		if (response.statusCode() != 200) {
			response.body().close();

			throw new IOException("HTTP " + response.statusCode());
		}

		failure = null;

		try (DataInputStream in = new DataInputStream(response.body())) {
			byte[] scratch = new byte[0];

			while (running) {
				int magic = in.readInt();

				if (magic != MAGIC) {
					throw new IOException("mất khung, magic " + Integer.toHexString(magic));
				}

				int length = in.readInt();

				if (length <= 0 || length > MAX_BLOCK_BYTES) {
					throw new IOException("khối âm thanh dài bất thường: " + length);
				}

				// the capture time. Read because it is part of the framing, and
				// dropped because nothing here resamples: the blocks are played in
				// the order they were recorded, at the rate they were recorded.
				in.readLong();

				if (scratch.length < length) {
					scratch = new byte[length];
				}

				in.readFully(scratch, 0, length);
				publish(scratch, length);
			}
		}
	}

	/**
	 * Turns one block of little-endian bytes into samples and queues it.
	 *
	 * The conversion is explicit rather than a ByteBuffer view because the wire
	 * is little-endian whatever the machine is, and a client on a big-endian one
	 * should hear the screen rather than static.
	 */
	private void publish(byte[] bytes, int length) {
		int samples = length / 2;
		short[] block = new short[samples];

		for (int index = 0; index < samples; index++) {
			int at = index * 2;

			block[index] = (short) ((bytes[at] & 0xFF) | (bytes[at + 1] << 8));
		}

		blocks.add(block);

		if (depth.incrementAndGet() > MAX_QUEUED) {
			trim(MAX_QUEUED);
		}
	}
}
