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
 * One screen's picture, fetched and decoded on its own thread.
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

	/** Waits between reconnects, widening so a dead server is not hammered. */
	private static final long[] RETRY_BACKOFF_MS = { 1_000L, 3_000L, 10_000L, 30_000L };

	private final String name;

	/** Built per attempt: the token inside dies with the server that issued it. */
	private final Supplier<String> url;

	private final int width;
	private final int height;

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

	/** Reused across frames; ImageIO would otherwise rebuild it every time. */
	private ImageReader reader;
	private BufferedImage target;

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

		Frame last = pending.getAndSet(null);

		if (last != null) {
			last.free();
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
		byte[] buffer = new byte[64 * 1024];
		byte[] window = new byte[0];

		while (running) {
			long before = System.nanoTime();
			int read = body.read(buffer);

			readStall.addAndGet(System.nanoTime() - before);

			if (read < 0) {
				return;
			}

			window = append(window, buffer, read);

			// Drain whatever else has already arrived. Without this the loop below
			// only ever sees one frame at a time, so a decoder that cannot keep up
			// works through a growing backlog instead of skipping it, and the
			// picture drifts further behind the sound the longer it runs.
			while (body.available() > 0 && window.length < MAX_WINDOW_BYTES) {
				int more = body.read(buffer);

				if (more < 0) {
					break;
				}

				received.addAndGet(more);
				window = append(window, buffer, more);
			}

			byte[] newest = null;
			long newestPts = -1;
			int skipped = 0;

			while (true) {
				int start = indexOf(window, boundary, 0);

				if (start < 0) {
					break;
				}

				int headerEnd = indexOf(window, new byte[] { 13, 10, 13, 10 }, start);

				if (headerEnd < 0) {
					break;
				}

				String headers = new String(window, start, headerEnd - start,
					java.nio.charset.StandardCharsets.US_ASCII);
				int length = intHeader(headers, "content-length:");

				if (length < 0) {
					break;
				}

				int bodyAt = headerEnd + 4;

				if (window.length < bodyAt + length) {
					break;
				}

				if (length > 0 && headers.toLowerCase(java.util.Locale.ROOT).contains("image/jpeg")) {
					if (newest != null) {
						skipped++;
					}

					newest = java.util.Arrays.copyOfRange(window, bodyAt, bodyAt + length);
					newestPts = longHeader(headers, PTS_HEADER);
				}

				window = java.util.Arrays.copyOfRange(window, bodyAt + length, window.length);
			}

			// Only the newest complete frame is decoded. A wall showing the present
			// is the whole point, and decoding a frame that is already superseded
			// buys nothing but latency.
			if (newest != null) {
				publish(newest, 0, newest.length, newestPts);

				if (skipped > 0 && dropped.addAndGet(skipped) == skipped) {
					LOGGER.info("Luna TV feed {} is skipping stale frames to keep up with the sound", name);
				}
			}

			// a part larger than anything sane means the stream is not what we
			// think it is; drop it rather than growing without bound
			if (window.length > MAX_WINDOW_BYTES) {
				throw new IOException("phần dữ liệu quá lớn, có thể không phải MJPEG");
			}
		}
	}

	private static byte[] append(byte[] window, byte[] chunk, int count) {
		byte[] grown = new byte[window.length + count];

		System.arraycopy(window, 0, grown, 0, window.length);
		System.arraycopy(chunk, 0, grown, window.length, count);

		return grown;
	}

	private void publish(byte[] data, int offset, int length, long pts) {
		try {
			long before = System.nanoTime();
			BufferedImage image = decode(data, offset, length);

			decodeStall.addAndGet(System.nanoTime() - before);

			if (image == null) {
				return;
			}

			byte[] source = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
			int sourceWidth = image.getWidth();
			int sourceHeight = image.getHeight();
			ByteBuffer rgba = MemoryUtil.memAlloc(sourceWidth * sourceHeight * 4);

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

			Frame previous = pending.getAndSet(new Frame(rgba, sourceWidth, sourceHeight, pts));

			if (previous != null) {
				previous.free();
			}
		} catch (Throwable throwable) {
			failure = "giải mã lỗi: " + throwable;
			LOGGER.warn("Luna TV feed {} could not decode a frame", name, throwable);
		}
	}

	private BufferedImage decode(byte[] data, int offset, int length) throws IOException {
		if (reader == null) {
			var readers = ImageIO.getImageReadersByFormatName("jpeg");

			if (!readers.hasNext()) {
				throw new IOException("JVM không có bộ giải mã JPEG");
			}

			reader = readers.next();
		}

		try (ImageInputStream stream = ImageIO.createImageInputStream(
				new ByteArrayInputStream(data, offset, length))) {
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

	private static int indexOf(byte[] haystack, byte[] needle, int from) {
		outer:
		for (int at = Math.max(0, from); at <= haystack.length - needle.length; at++) {
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
