package dev.belikhun.luna.tv.client.screen;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One screen's picture: a thread fetching and parsing the stream, and a small
 * team of threads decoding it.
 *
 * The decoders are what buy the frame rate. One JPEG decodes on one core, and
 * at a cinema-sized wall one core is a ceiling in the low twenties of frames a
 * second; separate frames are independent, and measured on a real frame the
 * scaling is nearly linear - two threads 1.9x, three 2.9x. Frames can then
 * finish out of order, which is why publishing compares capture times instead
 * of trusting arrival.
 *
 * The wire format is multipart/x-mixed-replace with a JPEG per part, which the
 * JDK can decode unaided - that is the whole reason the mod needs no native
 * library and stays a few tens of kilobytes. Each part carries the moment it was
 * captured, so audio can be lined up against it rather than guessed at.
 *
 * The render thread never decodes and never blocks: this thread produces a
 * finished RGBA buffer and publishes it newest-wins, and the renderer uploads
 * whatever is there when it draws. A frame that is replaced before it is drawn
 * cost only its decode, which is the correct trade for a wall that shows the
 * present.
 */
public final class StreamFeed implements VideoFeed {

	/** Marks the capture time of a part, in microseconds. */
	private static final String PTS_HEADER = "x-luna-pts";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** Nothing legitimate reaches this; past it the stream is not MJPEG. */
	private static final int MAX_WINDOW_BYTES = 32 * 1024 * 1024;

	/**
	 * How far ahead the drain below may read before parsing again.
	 *
	 * Only the newest frame of a batch is ever decoded, so reading further
	 * ahead than a handful of frames buys nothing and costs both memory and
	 * the time to walk past them. Two megabytes is roughly twenty frames of a
	 * wall this size, which is more than any burst worth skipping.
	 */
	private static final int DRAIN_AHEAD_BYTES = 2 * 1024 * 1024;

	/** The blank line that ends a part's headers. */
	private static final byte[] HEADER_END = { 13, 10, 13, 10 };

	/** Waits between reconnects, widening so a dead server is not hammered. */
	private static final long[] RETRY_BACKOFF_MS = { 1_000L, 3_000L, 10_000L, 30_000L };

	/**
	 * How many frames may decode at once.
	 *
	 * Two by default: it doubles the ceiling while leaving the rest of the
	 * machine to the game the wall is drawn inside, and -Dlunatv.mjpegThreads
	 * raises it on a machine with cores to spare, or pins it back to one.
	 */
	private static final int DECODE_THREADS = decodeThreads();

	private static int decodeThreads() {
		try {
			int wanted = Integer.parseInt(System.getProperty("lunatv.mjpegThreads", "2"));

			return Math.max(1, Math.min(4, wanted));
		} catch (NumberFormatException bad) {
			return 2;
		}
	}

	private final String name;

	/** Built per attempt: the token inside dies with the server that issued it. */
	private final Supplier<String> url;

	private final int width;
	private final int height;

	/**
	 * Finished frames kept for reuse.
	 *
	 * Three is enough for the one being filled, the one waiting to be drawn and
	 * the one being drawn. At this wall's size a frame is eight megabytes, so
	 * allocating per frame means faulting two thousand fresh pages fifteen or
	 * more times a second for pixels about to be overwritten anyway - the same
	 * trade the H.264 feed already makes.
	 */
	private static final int POOL_SIZE = 3;

	private final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> pool =
		new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** The newest decoded frame, waiting for the render thread. */
	private final AtomicReference<Frame> pending = new AtomicReference<>();
	private final AtomicLong decoded = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong received = new AtomicLong();

	// accumulated in nanoseconds and converted on read: per-chunk waits are
	// often under a millisecond, and truncating each would report zero forever
	private final AtomicLong readStall = new AtomicLong();
	private final AtomicLong decodeStall = new AtomicLong();

	private volatile Thread thread;
	private volatile boolean running;
	private volatile String failure;

	/**
	 * The one frame waiting to be decoded, newest-wins.
	 *
	 * A single slot rather than a queue on purpose: when every decoder is
	 * mid-frame, a fresher arrival replaces the waiting one, because decoding
	 * a superseded frame buys latency and nothing else.
	 */
	private final AtomicReference<Job> waiting = new AtomicReference<>();

	private Thread[] decoders = new Thread[0];

	/** A parsed frame on its way to a decoder. */
	private record Job(byte[] jpeg, long pts) {
	}

	public StreamFeed(String name, Supplier<String> url, int width, int height) {
		this.name = name;
		this.url = url;
		this.width = width;
		this.height = height;
	}

	/** Starts fetching. */
	@Override
	public void start() {
		if (running) {
			return;
		}

		running = true;
		thread = new Thread(this::pump, "LunaTv-Feed-" + name);
		thread.setDaemon(true);
		thread.start();

		decoders = new Thread[DECODE_THREADS];

		for (int index = 0; index < decoders.length; index++) {
			decoders[index] = new Thread(this::decodeLoop, "LunaTv-Feed-" + name + "-dec" + index);
			decoders[index].setDaemon(true);
			decoders[index].start();
		}
	}

	/**
	 * Takes the newest frame, if one arrived since the last call.
	 *
	 * @return the frame, or null when nothing new was decoded
	 */
	@Override
	public Frame poll() {
		return pending.getAndSet(null);
	}

	@Override
	public long framesDecoded() {
		return decoded.get();
	}

	@Override
	public long bytesReceived() {
		return received.get();
	}

	@Override
	public long readStallMillis() {
		return readStall.get() / 1_000_000L;
	}

	@Override
	public long decodeStallMillis() {
		return decodeStall.get() / 1_000_000L;
	}

	/** Frames thrown away undecoded because a newer one had already arrived. */
	@Override
	public long framesDropped() {
		return dropped.get();
	}

	/** Why the feed is unhappy, or null. */
	@Override
	public String failure() {
		return failure;
	}

	@Override
	public String codec() {
		return "mjpeg";
	}

	@Override
	public void close() {
		running = false;

		Thread current = thread;

		if (current != null) {
			current.interrupt();
		}

		thread = null;

		for (Thread decoder : decoders) {
			decoder.interrupt();
		}

		decoders = new Thread[0];
		waiting.set(null);

		Frame last = pending.getAndSet(null);

		if (last != null) {
			last.free();
		}

		while (true) {
			ByteBuffer spare = pool.poll();

			if (spare == null) {
				break;
			}

			MemoryUtil.memFree(spare);
		}
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
				// recorded and said out loud: a feed that quietly retries for ever
				// looks exactly like a screen that was never sent
				LOGGER.warn("Luna TV feed {} failed: {}", name, throwable.toString());
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
			.header("Accept", "multipart/x-mixed-replace")
			.GET()
			.build();

		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() != 200) {
			throw new IOException("máy chủ trả về " + response.statusCode());
		}

		LOGGER.info("Luna TV feed {} connected", name);

		String contentType = response.headers().firstValue("content-type").orElse("");
		int at = contentType.indexOf("boundary=");

		if (at < 0) {
			throw new IOException("thiếu boundary trong " + contentType);
		}

		byte[] boundary = ("--" + contentType.substring(at + "boundary=".length()).trim())
			.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

		failure = null;

		try (InputStream body = response.body()) {
			consume(body, boundary);
		}
	}

	/**
	 * Walks the multipart stream, decoding each JPEG part.
	 *
	 * Parts are read by their declared length rather than by scanning for the
	 * next boundary: a JPEG can contain the boundary bytes by chance, and a
	 * scanner would then split a frame in half.
	 */
	private void consume(InputStream body, byte[] boundary) throws IOException {
		byte[] chunk = new byte[64 * 1024];
		Window window = new Window();

		while (running) {
			long before = System.nanoTime();
			int read = body.read(chunk);

			readStall.addAndGet(System.nanoTime() - before);

			if (read < 0) {
				return;
			}

			received.addAndGet(read);
			window.add(chunk, read);

			// Drain whatever else has already arrived. Without this the loop below
			// only ever sees one frame at a time, so a decoder that cannot keep up
			// works through a growing backlog instead of skipping it, and the
			// picture drifts further behind the sound the longer it runs. Bounded,
			// because at this wall's bitrate `available()` is never zero for long
			// and an unbounded drain simply never returns to the parsing.
			while (body.available() > 0 && window.size() < DRAIN_AHEAD_BYTES) {
				int more = body.read(chunk);

				if (more < 0) {
					break;
				}

				received.addAndGet(more);
				window.add(chunk, more);
			}

			// Where the newest complete frame sits, rather than a copy of it:
			// every part before it is superseded, and copying each one out only
			// to drop it is the work this loop exists to avoid.
			int newestAt = -1;
			int newestLength = 0;
			long newestPts = -1;
			int skipped = 0;

			while (true) {
				int start = indexOf(window.bytes, boundary, window.at, window.fill);

				if (start < 0) {
					break;
				}

				int headerEnd = indexOf(window.bytes, HEADER_END, start, window.fill);

				if (headerEnd < 0) {
					break;
				}

				String headers = new String(window.bytes, start, headerEnd - start,
					java.nio.charset.StandardCharsets.US_ASCII);
				int length = intHeader(headers, "content-length:");

				if (length < 0) {
					break;
				}

				int bodyAt = headerEnd + 4;

				if (window.fill < bodyAt + length) {
					break;
				}

				if (length > 0 && headers.toLowerCase(java.util.Locale.ROOT).contains("image/jpeg")) {
					if (newestAt >= 0) {
						skipped++;
					}

					newestAt = bodyAt;
					newestLength = length;
					newestPts = longHeader(headers, PTS_HEADER);
				}

				window.at = bodyAt + length;
			}

			// Only the newest complete frame goes to a decoder. A wall showing
			// the present is the whole point, and decoding a frame that is
			// already superseded buys nothing but latency. Copied out of the
			// window, because the window reuses its array under the next read
			// while a decoder is still holding the job.
			if (newestAt >= 0) {
				hand(java.util.Arrays.copyOfRange(window.bytes, newestAt, newestAt + newestLength),
					newestPts);

				if (skipped > 0 && dropped.addAndGet(skipped) == skipped) {
					LOGGER.info("Luna TV feed {} is skipping stale frames to keep up with the sound", name);
				}
			}

			// a part larger than anything sane means the stream is not what we
			// think it is; drop it rather than growing without bound
			if (window.size() > MAX_WINDOW_BYTES) {
				throw new IOException("phần dữ liệu quá lớn, có thể không phải MJPEG");
			}
		}
	}

	/**
	 * The bytes that have arrived but not yet been parsed, as a cursor over one
	 * array.
	 *
	 * The obvious shape - one array reallocated per read, another per part
	 * consumed - is quadratic in the window, and at this wall's real MJPEG
	 * bitrate that is not a slow path but a stall. Measured on a 1920x1024
	 * screen taking 35 Mbit/s: the feed spent every cycle copying multi-megabyte
	 * windows, decoded nothing at all for three minutes, and took the client
	 * down with it. Here the bytes stay put and a cursor moves over them; they
	 * are shifted only when the array actually needs the room back.
	 */
	private static final class Window {

		private byte[] bytes = new byte[1 << 20];

		/** How much of the array holds data. */
		private int fill;

		/** Where the next unparsed part starts; everything before it is spent. */
		private int at;

		private void add(byte[] chunk, int count) {
			if (fill + count > bytes.length) {
				reclaim();
			}

			if (fill + count > bytes.length) {
				byte[] grown = new byte[Math.max(bytes.length * 2, fill + count)];

				System.arraycopy(bytes, 0, grown, 0, fill);
				bytes = grown;
			}

			System.arraycopy(chunk, 0, bytes, fill, count);
			fill += count;
		}

		/** Moves what is left to the front: the one time bytes move at all. */
		private void reclaim() {
			if (at == 0) {
				return;
			}

			System.arraycopy(bytes, at, bytes, 0, fill - at);
			fill -= at;
			at = 0;
		}

		/** How many bytes are still waiting to be parsed. */
		private int size() {
			return fill - at;
		}
	}

	/**
	 * A frame buffer from the pool, or a new one when the pool is empty.
	 *
	 * A buffer of the wrong size is released rather than reshaped: the wall can
	 * change size under a running feed, and a pool of stale sizes would hand
	 * back a buffer too small for the frame going into it.
	 *
	 * @param bytes how big the frame is
	 * @return an off-heap buffer of exactly that size
	 */
	private ByteBuffer take(int bytes) {
		while (true) {
			ByteBuffer reused = pool.poll();

			if (reused == null) {
				return MemoryUtil.memAlloc(bytes);
			}

			if (reused.capacity() == bytes) {
				return reused;
			}

			MemoryUtil.memFree(reused);
		}
	}

	/**
	 * Takes a frame buffer back.
	 *
	 * Past the pool size they are released rather than kept, so a feed that is
	 * closing does not sit on twenty megabytes nobody will ask for again.
	 */
	private void recycle(ByteBuffer buffer) {
		if (!running || pool.size() >= POOL_SIZE) {
			MemoryUtil.memFree(buffer);

			return;
		}

		pool.add(buffer);
	}

	/**
	 * Hands a frame to whichever decoder is free first.
	 *
	 * @param jpeg the frame, owned by the job from here on
	 * @param pts when the server captured it, in microseconds
	 */
	private void hand(byte[] jpeg, long pts) {
		// a part without a capture time still needs an order the publish can
		// compare by; the clock is microseconds too, so the scale matches
		long ordered = pts >= 0 ? pts : System.nanoTime() / 1_000L;

		Job replaced = waiting.getAndSet(new Job(jpeg, ordered));

		if (replaced != null) {
			dropped.incrementAndGet();
		}

		for (Thread decoder : decoders) {
			java.util.concurrent.locks.LockSupport.unpark(decoder);
		}
	}

	/**
	 * One decoder's life: take the waiting frame, decode it, publish it.
	 *
	 * Each decoder owns its reader and its destination image, because ImageIO
	 * readers are stateful and two threads sharing one corrupt frames.
	 */
	private void decodeLoop() {
		Decoder own = new Decoder();

		while (running) {
			Job job = waiting.getAndSet(null);

			if (job == null) {
				// parked briefly rather than on a lock: hand() unparks on every
				// frame, and the timeout only bounds how late a missed unpark
				// can make one
				java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);

				continue;
			}

			try {
				long before = System.nanoTime();
				BufferedImage image = own.decode(job.jpeg());

				decodeStall.addAndGet(System.nanoTime() - before);

				if (image != null) {
					deliver(image, job.pts());
				}
			} catch (Throwable throwable) {
				failure = "giải mã lỗi: " + throwable;
				LOGGER.warn("Luna TV feed {} could not decode a frame", name, throwable);
			}
		}
	}

	/**
	 * Converts a decoded image and publishes it, newest capture first.
	 *
	 * Ordered by when the server captured the frame, not by when its decode
	 * finished: with several decoders an older frame can finish after a newer
	 * one, and letting it overwrite would make the wall step backwards.
	 */
	private void deliver(BufferedImage image, long pts) {
		byte[] source = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		ByteBuffer rgba = take(sourceWidth * sourceHeight * 4);

		// straight from the decoder's BGR raster into the RGBA the texture
		// upload wants, one pass, no intermediate image
		for (int index = 0, at = 0; index < sourceWidth * sourceHeight; index++) {
			int base = index * 3;

			rgba.put(at++, source[base + 2]);
			rgba.put(at++, source[base + 1]);
			rgba.put(at++, source[base]);
			rgba.put(at++, (byte) 0xFF);
		}

		if (decoded.incrementAndGet() == 1) {
			LOGGER.info("Luna TV feed {} decoded its first frame, {}x{}",
				name, sourceWidth, sourceHeight);
		}

		Frame fresh = new Frame(rgba, sourceWidth, sourceHeight, pts, this::recycle);

		while (true) {
			Frame current = pending.get();

			if (current != null && current.pts() >= pts) {
				fresh.free();
				dropped.incrementAndGet();

				return;
			}

			if (pending.compareAndSet(current, fresh)) {
				if (current != null) {
					current.free();
				}

				return;
			}
		}
	}

	/** One decoder's reusable state: its reader, and the image it decodes into. */
	private static final class Decoder {

		private ImageReader reader;
		private BufferedImage target;

		private BufferedImage decode(byte[] data) throws IOException {
			if (reader == null) {
				var readers = ImageIO.getImageReadersByFormatName("jpeg");

				if (!readers.hasNext()) {
					throw new IOException("JVM không có bộ giải mã JPEG");
				}

				reader = readers.next();
			}

			try (ImageInputStream stream = ImageIO.createImageInputStream(
					new ByteArrayInputStream(data))) {
				reader.setInput(stream, true, true);

				int frameW = reader.getWidth(0);
				int frameH = reader.getHeight(0);

				if (target == null || target.getWidth() != frameW || target.getHeight() != frameH) {
					target = new BufferedImage(frameW, frameH, BufferedImage.TYPE_3BYTE_BGR);
				}

				ImageReadParam param = reader.getDefaultReadParam();

				param.setDestination(target);

				return reader.read(0, param);
			} finally {
				if (reader != null) {
					reader.setInput(null);
				}
			}
		}
	}

	/**
	 * Finds a byte pattern inside part of an array.
	 *
	 * @param haystack the bytes to look in
	 * @param needle the bytes to look for
	 * @param from where to start looking
	 * @param end where to stop: nothing at or past this index is data
	 * @return the index of the first match, or -1
	 */
	private static int indexOf(byte[] haystack, byte[] needle, int from, int end) {
		outer:
		for (int at = Math.max(0, from); at <= end - needle.length; at++) {
			for (int index = 0; index < needle.length; index++) {
				if (haystack[at + index] != needle[index]) {
					continue outer;
				}
			}

			return at;
		}

		return -1;
	}

	private static int intHeader(String headers, String name) {
		long value = longHeader(headers, name);

		return value < 0 ? -1 : (int) value;
	}

	private static long longHeader(String headers, String name) {
		for (String line : headers.split("\r\n")) {
			if (!line.toLowerCase(java.util.Locale.ROOT).startsWith(name)) {
				continue;
			}

			try {
				return Long.parseLong(line.substring(name.length()).trim());
			} catch (NumberFormatException malformed) {
				return -1;
			}
		}

		return -1;
	}
}
