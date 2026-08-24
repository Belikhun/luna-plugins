package dev.belikhun.luna.tv.client.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One screen's picture, decoded by a child ffmpeg.
 *
 * The wire carries a bare H.264 elementary stream: no container, no framing of
 * ours, no timestamps. That is not laziness, it is the shortest path - ffmpeg
 * already knows how to find frame boundaries in Annex-B, so the network side of
 * this class does nothing but copy bytes from a socket into a pipe.
 *
 * ffmpeg is a child process rather than a linked library. A decoder is a large
 * amount of C being fed data from the internet, and the difference between the
 * two choices is whether a bad frame ends a child process or takes the whole
 * game down with it. The cost is one pipe in each direction, which at 720p is
 * a few megabytes a second of memcpy and not worth optimising away.
 *
 * Out of ffmpeg comes raw RGBA at a size fixed by the command line, so the
 * reader needs no parsing at all: every width * height * 4 bytes is one frame.
 */
public final class H264Feed implements VideoFeed {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/** Waits between reconnects, widening so a dead server is not hammered. */
	private static final long[] RETRY_BACKOFF_MS = { 1_000L, 3_000L, 10_000L, 30_000L };

	/** Bytes moved between the socket and the decoder at a time. */
	private static final int PIPE_CHUNK = 32 * 1024;

	/** Overrides the hardware decode choice: off, d3d11va, dxva2, auto, ... */
	private static final String HWACCEL_PROPERTY = "lunatv.hwaccel";

	/**
	 * Connections that may end without a single frame before hardware decode is
	 * written off for the session.
	 *
	 * Two rather than one because the first can end for reasons that have
	 * nothing to do with the decoder - the screen switched off, the server
	 * restarted - and giving up the offload on that would be wrong.
	 */
	private static final int HWACCEL_STRIKES = 2;

	/**
	 * Finished frames kept for reuse.
	 *
	 * Three is enough for the one being filled, the one waiting to be drawn and
	 * the one being drawn. At this size a frame is six megabytes, so allocating
	 * per frame means faulting fifteen hundred pages sixty times a second.
	 */
	private static final int POOL_SIZE = 3;

	private final String name;

	/**
	 * Built fresh for every connection attempt, never kept.
	 *
	 * The URL carries the access token, and the token dies with the server
	 * process that issued it. A feed opened moments after a server restart
	 * starts with the old session's token, is refused, and the replacement
	 * arrives in the next registry message a second later - so a feed that
	 * froze its URL at construction would retry the dead token forever, which
	 * is exactly what it used to do.
	 */
	private final Supplier<String> url;

	private final Path ffmpeg;
	private final int width;
	private final int height;
	private final int frameBytes;

	private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
	private final AtomicReference<Frame> pending = new AtomicReference<>();
	private final AtomicLong decoded = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong received = new AtomicLong();

	private volatile Thread thread;
	private volatile boolean running;
	private volatile String failure;
	private volatile Process decoder;

	/** Whether to keep asking for GPU decode, and how often it has come to nothing. */
	private volatile boolean hardware = true;
	private volatile int barren;

	public H264Feed(String name, Supplier<String> url, Path ffmpeg, int width, int height) {
		this.name = name;
		this.url = url;
		this.ffmpeg = ffmpeg;
		this.width = width;
		this.height = height;
		this.frameBytes = width * height * 4;
	}

	@Override
	public void start() {
		if (running) {
			return;
		}

		running = true;
		thread = new Thread(this::pump, "LunaTv-H264-" + name);
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public Frame poll() {
		return pending.getAndSet(null);
	}

	@Override
	public String failure() {
		return failure;
	}

	@Override
	public String codec() {
		return "h264";
	}

	@Override
	public long framesDecoded() {
		return decoded.get();
	}

	/** Frames finished but replaced before the renderer took them. */
	@Override
	public long framesDropped() {
		return dropped.get();
	}

	@Override
	public long bytesReceived() {
		return received.get();
	}

	@Override
	public void close() {
		running = false;

		Process child = decoder;

		decoder = null;

		if (child != null) {
			child.destroyForcibly();
		}

		Thread current = thread;

		if (current != null) {
			current.interrupt();
		}

		thread = null;

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
				LOGGER.warn("Luna TV h264 {} failed: {}", name, throwable.toString());
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
			.header("Accept", "video/h264")
			.GET()
			.build();

		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() != 200) {
			response.body().close();

			throw new IOException("HTTP " + response.statusCode());
		}

		long before = decoded.get();
		Process child = new ProcessBuilder(command())
			.redirectErrorStream(false)
			.start();

		decoder = child;
		failure = null;
		String using = hwaccel();

		LOGGER.info("Luna TV h264 {} connected, decoding with pid {} ({})",
			name, child.pid(), using.isEmpty() ? "software" : using);

		Thread frames = daemon("LunaTv-H264-out-" + name, () -> collect(child.getInputStream()));
		Thread noise = daemon("LunaTv-H264-err-" + name, () -> complain(child.getErrorStream()));

		try (InputStream body = response.body(); OutputStream into = child.getOutputStream()) {
			byte[] buffer = new byte[PIPE_CHUNK];

			while (running) {
				int more = body.read(buffer);

				if (more < 0) {
					break;
				}

				received.addAndGet(more);
				into.write(buffer, 0, more);
				into.flush();
			}
		} finally {
			child.destroyForcibly();
			frames.interrupt();
			noise.interrupt();

			if (decoder == child) {
				decoder = null;
			}

			judge(before);
		}
	}

	/**
	 * Decides whether hardware decode is doing anything.
	 *
	 * It cannot be asked directly: ffmpeg answers a hwaccel it cannot initialise
	 * with a warning on stderr and carries on in software, and the shapes of
	 * those warnings differ per driver, so matching them would be guesswork.
	 * A connection that produced no frames at all is the signal that survives
	 * that uncertainty, and after a couple in a row the offload is dropped
	 * rather than left to fail silently for the rest of the session.
	 *
	 * @param before how many frames had been decoded when this connection opened
	 */
	private void judge(long before) {
		if (!hardware) {
			return;
		}

		if (decoded.get() > before) {
			barren = 0;

			return;
		}

		barren++;

		if (barren < HWACCEL_STRIKES) {
			return;
		}

		hardware = false;
		LOGGER.warn("Luna TV h264 {}: hardware decode produced nothing twice, falling back to software",
			name);
	}

	/**
	 * The decode command line, which is mostly about not adding latency.
	 *
	 * low_delay and a tiny probesize keep ffmpeg from holding frames back while
	 * it works out what the stream is. `-fflags nobuffer` belongs in that list
	 * by reputation and is deliberately absent: measured against a 120-frame
	 * clip it discards everything decoded during the probe, twenty frames of
	 * black at the start of every connection, and buys nothing the other two
	 * flags do not already give.
	 *
	 * fps_mode passthrough is what stops ffmpeg conforming the output to a
	 * frame rate it guessed. A bare elementary stream carries no timing, so the
	 * guess is always wrong, and the correction is always frames duplicated or
	 * thrown away. One frame in, one frame out.
	 *
	 * The size is forced rather than taken from the stream so the reader can
	 * slice stdout arithmetically; if the server ever encodes something else,
	 * ffmpeg scales it and the frames stay the size this class expects.
	 *
	 * Threading is the flag that decides the latency, and it has to be slice
	 * threading. The default is frame threading, which runs the cores in a
	 * pipeline one frame apart, so the first picture only comes out once every
	 * thread holds a frame: eight cores at sixty frames a second is 133ms of
	 * delay that never goes away. Slice threading splits each frame instead,
	 * which works here because the encoder is told to produce sliced frames for
	 * exactly this reason, and measured on a 600-frame clip it is also the
	 * fastest of the three modes. Pinning one thread was tried and is wrong the
	 * other way; this is the setting with no downside.
	 */
	private List<String> command() {
		List<String> command = new ArrayList<>();

		command.add(ffmpeg.toString());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");

		// GPU decode where the shipped binary has it. Deliberately not fatal:
		// ffmpeg treats a hwaccel that fails to initialise as a warning and
		// decodes in software, so a machine with odd drivers loses nothing but
		// the offload. -Dlunatv.hwaccel=off (or another method name) overrides.
		String hwaccel = hwaccel();

		if (!hwaccel.isEmpty()) {
			command.add("-hwaccel");
			command.add(hwaccel);
		}

		command.add("-flags");
		command.add("low_delay");
		command.add("-probesize");
		command.add("32");
		command.add("-analyzeduration");
		command.add("0");
		command.add("-thread_type");
		command.add("slice");

		command.add("-f");
		command.add("h264");
		command.add("-i");
		command.add("pipe:0");
		command.add("-fps_mode");
		command.add("passthrough");
		command.add("-s");
		command.add(width + "x" + height);
		command.add("-pix_fmt");
		command.add("rgba");
		command.add("-f");
		command.add("rawvideo");
		command.add("pipe:1");

		return command;
	}

	/**
	 * Which hardware decoder to ask for, or empty for software.
	 *
	 * d3d11va by default on Windows, because that is the one platform whose
	 * decoder we ship and therefore know carries it. Everywhere else the mod is
	 * running whatever ffmpeg was on PATH, whose hwaccel set is unknowable, so
	 * hardware decode there is opt-in through the property rather than a guess.
	 */
	private String hwaccel() {
		if (!hardware) {
			return "";
		}

		String chosen = System.getProperty(HWACCEL_PROPERTY);

		if (chosen != null) {
			String trimmed = chosen.trim();

			if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("off")) {
				return "";
			}

			return trimmed;
		}

		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);

		return os.contains("win") ? "d3d11va" : "";
	}

	/**
	 * Reads finished pictures out of the decoder, newest-wins.
	 *
	 * A frame replaced before it was drawn cost only its decode, which is the
	 * right trade for a wall that shows the present. It is also the only place
	 * frames may be discarded: doing it earlier, on the coded stream, would not
	 * skip a picture but corrupt every one after it.
	 */
	private void collect(InputStream from) {
		// straight from the pipe into the buffer the texture upload will read,
		// with no heap array in between: at sixty frames a second the copy this
		// avoids is a third of a gigabyte every second, for nothing
		ReadableByteChannel channel = Channels.newChannel(from);

		try {
			while (running) {
				ByteBuffer rgba = take();

				rgba.clear();

				while (rgba.hasRemaining()) {
					if (channel.read(rgba) < 0) {
						recycle(rgba);

						return;
					}
				}

				rgba.flip();
				decoded.incrementAndGet();

				Frame previous = pending.getAndSet(
					new Frame(rgba, width, height, 0L, this::recycle));

				if (previous != null) {
					dropped.incrementAndGet();
					previous.free();
				}
			}
		} catch (IOException ended) {
			// the decoder was killed, which is the normal way this stops
		}
	}

	/** A frame buffer from the pool, or a new one when the pool is empty. */
	private ByteBuffer take() {
		ByteBuffer reused = pool.poll();

		return reused != null ? reused : MemoryUtil.memAlloc(frameBytes);
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

	private void complain(InputStream from) {
		try (java.io.BufferedReader lines = new java.io.BufferedReader(
				new java.io.InputStreamReader(from, java.nio.charset.StandardCharsets.UTF_8))) {
			String line;

			while ((line = lines.readLine()) != null) {
				failure = line;
				LOGGER.warn("Luna TV h264 {}: {}", name, line);
			}
		} catch (IOException ended) {
			// same
		}
	}

	private Thread daemon(String name, Runnable body) {
		Thread started = new Thread(body, name);

		started.setDaemon(true);
		started.start();

		return started;
	}
}
