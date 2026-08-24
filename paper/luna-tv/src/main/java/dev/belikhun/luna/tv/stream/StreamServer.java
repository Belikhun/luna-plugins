package dev.belikhun.luna.tv.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;

/**
 * Serves each screen's frames to client mods, off the Minecraft connection.
 *
 * The point is not only bandwidth. Map packets ride the player's own TCP
 * connection through the proxy, so a multi-megabyte frame queued ahead of a
 * movement packet delays it - head-of-line blocking that no amount of spare
 * bandwidth fixes. A separate socket removes that even before the twenty-fold
 * drop in bytes that sending JPEG instead of palette-quantised maps gives.
 *
 * The wire format is deliberately the oldest, dullest thing that works:
 * multipart/x-mixed-replace, one JPEG per part. Chromium already hands us JPEG,
 * so nothing is transcoded here - the bytes are forwarded exactly as they
 * arrive - and every client can decode them with the JDK's own ImageIO, which
 * is what keeps the mod free of native decoders and therefore small and
 * portable. A browser pointed at the same URL shows the stream too, which is
 * how this is tested without a game client.
 */
public final class StreamServer {

	/** Part separator; any token works as long as both ends agree. */
	private static final String BOUNDARY = "lunatvframe";

	/** How long an issued token stays valid. */
	private static final long TOKEN_TTL_MS = 60 * 60 * 1000L;

	/** Frames a connection may fall behind before the older one is dropped. */
	private static final long WRITE_PARK_NANOS = 2_000_000L;

	/**
	 * How much coded video is kept to paint a newcomer with.
	 *
	 * One keyframe interval's worth in the normal case. The cap is what stops a
	 * screen that never reaches a keyframe - a broken encoder, a stream at some
	 * unexpected setting - from growing this without bound; past it the buffer
	 * is dropped and a joiner simply waits for the next keyframe instead.
	 */
	private static final int MAX_GOP_BYTES = 4 * 1024 * 1024;

	/** Header naming a part's capture time, in microseconds. */
	private static final String PTS_HEADER = "X-Luna-Pts";

	/** Magic in front of every audio block, so the framing is unambiguous. */
	private static final int AUDIO_MAGIC = 0x4C545641;

	/**
	 * The clock both media share.
	 *
	 * Video and audio travel over separate sockets with separate buffering, so
	 * nothing about their arrival order says anything about when they were
	 * captured. Stamping both from one monotonic clock is what lets a client
	 * line them up rather than guess, and guessing is what audio-video drift
	 * actually is.
	 */
	private static long nowMicros() {
		return System.nanoTime() / 1_000L;
	}

	private final LunaLogger logger;

	private final Map<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
	private final Map<String, Set<Subscriber>> listeners = new ConcurrentHashMap<>();

	/**
	 * The last frame each screen painted, for whoever connects next.
	 *
	 * A screencast only fires when the page changes, so a screen sitting on a
	 * search box or a finished article produces nothing at all - correctly, and
	 * indistinguishably from a fault, because a viewer who arrives during that
	 * quiet gets an open socket and a blank wall until the page happens to move.
	 * One frame per screen is what closes that: the newcomer is painted the
	 * present immediately and then follows the stream like everybody else.
	 */
	private final Map<String, Stamped> latest = new ConcurrentHashMap<>();

	/** Everyone taking a screen as H.264 rather than as a sequence of pictures. */
	private final Map<String, Set<Subscriber>> compressed = new ConcurrentHashMap<>();

	/** One encoder per screen anybody is watching that way. */
	private final Map<String, H264Encoder> encoders = new ConcurrentHashMap<>();

	/** The coded bytes since each screen's last keyframe, to prime a joiner. */
	private final Map<String, Gop> gops = new ConcurrentHashMap<>();

	/** Builds a screen's encoder; supplied by the plugin, which knows its size. */
	private volatile EncoderFactory encoderFactory = (screen, sink) -> null;

	/** Whether an ffmpeg was found at startup, and so whether H.264 is offered. */
	private volatile boolean h264;

	/** The render node the encoders may use, null until a probe has passed. */
	private volatile String vaapiDevice;

	/** Asked to start a screen's capture when somebody wants to hear it. */
	private volatile java.util.function.Predicate<String> audioTap = name -> false;

	/** Per-screen stream frame rate; falls back to the global setting. */
	private volatile java.util.function.ToIntFunction<String> fpsFor = name -> 0;

	/** Per-screen per-viewer ceiling; falls back to the global setting. */
	private volatile java.util.function.ToIntFunction<String> megabitsFor = name -> 0;
	private final Map<String, Grant> tokens = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();

	private volatile TvConfig config;
	private volatile HttpServer http;

	public StreamServer(LunaLogger logger, TvConfig config) {
		this.logger = logger;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/**
	 * Supplies the hook that attaches a screen's PCM to this server.
	 *
	 * @param tap returns true when the named screen now feeds {@link #publishAudio}
	 */
	public void audioTap(java.util.function.Predicate<String> tap) {
		this.audioTap = tap;
	}

	/**
	 * Supplies the builder for a screen's H.264 encoder.
	 *
	 * The plugin owns it because the numbers an encoder needs - the screen's
	 * size, its frame rate, its bitrate - live in the screen registry, and this
	 * class deliberately knows nothing about screens beyond their names.
	 *
	 * @param factory returns a fresh encoder, or null when the screen is gone
	 */
	public void encoderFactory(EncoderFactory factory) {
		this.encoderFactory = factory;
	}

	/** Whether this server can offer H.264 at all. */
	public boolean h264() {
		return h264;
	}

	/**
	 * Supplies the per-screen pacing values.
	 *
	 * @param fps resolves a screen's stream frame rate
	 * @param megabits resolves a screen's per-viewer ceiling
	 */
	public void limits(
		java.util.function.ToIntFunction<String> fps,
		java.util.function.ToIntFunction<String> megabits
	) {
		this.fpsFor = fps;
		this.megabitsFor = megabits;
	}

	/**
	 * Hands a block of PCM to everyone listening to a screen.
	 *
	 * @param screen the screen's name
	 * @param pcm 48kHz signed 16-bit little endian, interleaved when stereo
	 */
	public void publishAudio(String screen, byte[] pcm) {
		Set<Subscriber> hearing = listeners.get(key(screen));

		if (hearing == null || hearing.isEmpty()) {
			return;
		}

		Stamped block = new Stamped(pcm, nowMicros());

		for (Subscriber subscriber : hearing) {
			subscriber.offer(block);
			LockSupport.unpark(subscriber.thread);
		}
	}

	/**
	 * Binds the HTTP endpoint.
	 *
	 * @return true when it is listening
	 */
	public boolean start() {
		if (!config.streamEnabled()) {
			return false;
		}

		if (http != null) {
			return true;
		}

		try {
			HttpServer server = HttpServer.create(
				new InetSocketAddress(config.streamHost(), config.streamPort()), 16);

			// virtual threads: one connection parks on its screen's frames for as
			// long as somebody is watching, and a platform thread each would put a
			// hard ceiling on viewers for no reason
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.createContext("/tv/", this::handle);
			server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
			server.start();

			http = server;
			h264 = "h264".equals(config.streamCodec()) && probeFfmpeg();

			if (h264 && config.vaapiDevice() != null) {
				vaapiDevice = probeVaapi(config.vaapiDevice());
			}

			logger.info("Luồng hình đang phục vụ tại " + config.streamHost()
				+ ":" + config.streamPort() + " ("
				+ (h264 ? "H.264, mã hoá " + (vaapiDevice != null ? "GPU" : "CPU") : "MJPEG")
				+ ").");

			return true;
		} catch (IOException exception) {
			logger.warn("Không mở được cổng luồng hình " + config.streamPort()
				+ ": " + exception.getMessage());

			return false;
		}
	}

	/** Stops serving and drops every connection. */
	public void stop() {
		HttpServer server = http;

		http = null;

		if (server != null) {
			server.stop(0);
		}

		for (Set<Subscriber> set : subscribers.values()) {
			for (Subscriber subscriber : set) {
				subscriber.done = true;
				LockSupport.unpark(subscriber.thread);
			}
		}

		for (Set<Subscriber> set : listeners.values()) {
			for (Subscriber subscriber : set) {
				subscriber.done = true;
				LockSupport.unpark(subscriber.thread);
			}
		}

		for (Set<Subscriber> set : compressed.values()) {
			for (Subscriber subscriber : set) {
				subscriber.done = true;
				LockSupport.unpark(subscriber.thread);
			}
		}

		for (H264Encoder encoder : encoders.values()) {
			encoder.stop();
		}

		subscribers.clear();
		listeners.clear();
		compressed.clear();
		encoders.clear();
		gops.clear();
		latest.clear();
		tokens.clear();
	}

	/** Whether the endpoint is listening. */
	public boolean running() {
		return http != null;
	}

	/**
	 * Hands a frame to everyone watching a screen.
	 *
	 * Called on the decode thread, so it never blocks: a connection that cannot
	 * keep up simply loses the frame it had not written yet. A wall shows the
	 * present, and a backlog would only turn into latency.
	 *
	 * @param screen the screen's name
	 * @param jpeg the frame exactly as Chromium encoded it
	 */
	public void publish(String screen, byte[] jpeg) {
		Stamped frame = new Stamped(jpeg, nowMicros());

		// kept even with nobody watching: the whole point is to have something to
		// show the first person who arrives, and they arrive after the quiet
		latest.put(key(screen), frame);

		H264Encoder encoder = encoders.get(key(screen));

		if (encoder != null) {
			encoder.offer(jpeg);
		}

		Set<Subscriber> watching = subscribers.get(key(screen));

		if (watching == null || watching.isEmpty()) {
			return;
		}

		for (Subscriber subscriber : watching) {
			subscriber.pending.set(frame);
			LockSupport.unpark(subscriber.thread);
		}
	}

	/** Whether anybody is watching a screen over the stream. */
	public boolean watched(String screen) {
		Set<Subscriber> watching = subscribers.get(key(screen));

		return watching != null && !watching.isEmpty();
	}

	/**
	 * Mints a token for one player.
	 *
	 * @param player who may watch
	 * @return the token to hand that player
	 */
	public String issueToken(UUID player) {
		byte[] bytes = new byte[32];

		random.nextBytes(bytes);

		String token = HexFormat.of().formatHex(bytes);

		tokens.put(token, new Grant(player, System.currentTimeMillis() + TOKEN_TTL_MS));
		sweep();

		return token;
	}

	/** Drops a player's tokens, on quit. */
	public void revoke(UUID player) {
		tokens.entrySet().removeIf(entry -> entry.getValue().player.equals(player));
	}

	private void sweep() {
		long now = System.currentTimeMillis();

		tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String screen = path.substring("/tv/".length());
		boolean single = screen.endsWith("/frame");
		boolean sound = screen.endsWith("/audio");
		boolean coded = screen.endsWith("/h264");

		if (single) {
			screen = screen.substring(0, screen.length() - "/frame".length());
		}

		if (sound) {
			screen = screen.substring(0, screen.length() - "/audio".length());
		}

		if (coded) {
			screen = screen.substring(0, screen.length() - "/h264".length());
		}

		UUID viewer = grantee(exchange);

		if (screen.isEmpty() || viewer == null) {
			respond(exchange, 403, "forbidden");

			return;
		}

		Set<Subscriber> watching = subscribers.computeIfAbsent(key(screen),
			unused -> new CopyOnWriteArraySet<>());

		if (watching.size() >= config.streamMaxViewers()) {
			respond(exchange, 503, "too many viewers");

			return;
		}

		if (sound) {
			serveAudio(exchange, screen, viewer);

			return;
		}

		if (coded) {
			serveCoded(exchange, screen);

			return;
		}

		if (single) {
			serveOne(exchange, watching, screen);

			return;
		}

		serveStream(exchange, watching, screen);
	}

	/**
	 * One frame, for a health check or a look in a browser.
	 *
	 * Waits for the next frame rather than keeping a copy per screen: a static
	 * page paints nothing, and a snapshot nobody asked for would pin the last
	 * frame of every screen in memory for as long as the server runs.
	 */
	private void serveOne(HttpExchange exchange, Set<Subscriber> watching, String screen)
			throws IOException {
		Subscriber subscriber = new Subscriber();

		watching.add(subscriber);

		try {
			Stamped frame = latest.get(key(screen));

			if (frame == null) {
				frame = subscriber.await(5_000L);
			}

			if (frame == null) {
				respond(exchange, 504, "no frame");

				return;
			}

			exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
			exchange.getResponseHeaders().set(PTS_HEADER, Long.toString(frame.pts()));
			exchange.sendResponseHeaders(200, frame.bytes().length);
			exchange.getResponseBody().write(frame.bytes());
		} finally {
			watching.remove(subscriber);
			exchange.close();
		}
	}

	private void serveStream(HttpExchange exchange, Set<Subscriber> watching, String screen) throws IOException {
		Subscriber subscriber = new Subscriber();

		watching.add(subscriber);

		exchange.getResponseHeaders().set("Content-Type",
			"multipart/x-mixed-replace; boundary=" + BOUNDARY);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		// chunked: the stream ends when the viewer walks away, not at a length we
		// could state up front
		exchange.sendResponseHeaders(200, 0);

		OutputStream body = exchange.getResponseBody();

		try {
			Stamped opening = latest.get(key(screen));

			// Straight out, ahead of the pacing: this is the one frame whose whole
			// value is arriving now, and holding it back for a frame interval on a
			// page that may never paint again is the blank wall this exists to fix.
			if (opening != null) {
				write(body, opening);
			}

			while (!subscriber.done && http != null) {
				// Re-read every frame, not once at connect: these are live
				// settings, and a viewer whose screen is being tuned to fit their
				// link is exactly the viewer who must not have to reconnect for
				// the new number to reach them.
				int rate = fpsFor.applyAsInt(screen);
				int ceiling = megabitsFor.applyAsInt(screen);
				long minGapNanos = 1_000_000_000L
					/ Math.max(1, rate > 0 ? rate : config.streamFps());

				Stamped frame = subscriber.await(15_000L);

				if (frame == null) {
					// nothing painted for a while: a comment part keeps the
					// connection warm through a static page without sending a
					// picture nobody needs
					body.write(("--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n"
						+ "Content-Length: 0\r\n\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
					body.flush();

					continue;
				}

				// paced per connection, not per screen: one viewer on a thin link
				// must not slow the frame rate everybody else is getting
				if (!subscriber.due(minGapNanos, frame.bytes().length,
					ceiling > 0 ? ceiling : config.streamMegabits())) {
					continue;
				}

				write(body, frame);
			}
		} catch (IOException disconnected) {
			// the viewer closed the connection, which is the normal way this ends
		} finally {
			watching.remove(subscriber);
			exchange.close();
		}
	}

	/**
	 * One screen as an H.264 elementary stream, for as long as the viewer stays.
	 *
	 * The body is Annex-B and nothing else: no container, no framing of ours, no
	 * timestamps. The client pipes it straight into its own decoder, which is
	 * already built to find frame boundaries in exactly this byte stream, so any
	 * framing added here would only be something both ends had to agree about
	 * and neither end needed.
	 *
	 * Unlike a picture stream this one cannot be thinned. Every frame is
	 * described against the ones before it, so a chunk skipped to save bandwidth
	 * does not cost one frame, it corrupts everything up to the next keyframe.
	 * A viewer who falls too far behind is therefore disconnected rather than
	 * degraded; reconnecting costs them under a second and gives them a clean
	 * picture instead of a smeared one.
	 */
	private void serveCoded(HttpExchange exchange, String screen) throws IOException {
		if (!h264) {
			respond(exchange, 503, "h264 not available");

			return;
		}

		Set<Subscriber> watching = compressed.computeIfAbsent(key(screen),
			unused -> new CopyOnWriteArraySet<>());

		if (watching.size() >= config.streamMaxViewers()) {
			respond(exchange, 503, "too many viewers");

			return;
		}

		if (!attach(screen)) {
			respond(exchange, 503, "encoder unavailable");

			return;
		}

		Subscriber subscriber = new Subscriber();

		subscriber.lossless = true;

		exchange.getResponseHeaders().set("Content-Type", "video/h264");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);

		OutputStream body = exchange.getResponseBody();
		Gop gop = gops.computeIfAbsent(key(screen), unused -> new Gop());
		List<byte[]> opening;

		// Subscribing and copying the buffer together, so every chunk reaches the
		// newcomer exactly once: one that arrives first is in the copy, one that
		// arrives after is in the queue, and none can be in both.
		synchronized (gop) {
			opening = gop.copy();
			watching.add(subscriber);
		}

		try {
			// The picture, before anything new is encoded. A screen showing a page
			// that has finished painting produces no frames at all, so without
			// this a viewer arriving during that quiet gets an open socket and
			// nothing on it - not even the response head, which nothing forwards
			// until there are bytes behind it.
			for (byte[] chunk : opening) {
				body.write(chunk);
			}

			if (!opening.isEmpty()) {
				body.flush();
			}

			long shedSeen = 0L;

			while (!subscriber.done && http != null) {
				long shedNow = subscriber.shed.get();

				if (shedNow > shedSeen) {
					logger.info("Người xem luồng '" + screen + "' tụt lại; đã nhảy tới"
						+ " keyframe mới nhất (bỏ " + (shedNow - shedSeen) + " khối).");
					shedSeen = shedNow;
				}

				Stamped chunk = subscriber.await(15_000L);

				if (chunk == null) {
					// A quiet screen is normal; a quiet screen whose encoder has
					// died is a stream that ended. Ending the connection is what
					// makes the viewer come back, and coming back is what builds
					// the replacement encoder.
					H264Encoder current = encoders.get(key(screen));

					if (current == null || !current.alive()) {
						break;
					}

					continue;
				}

				body.write(chunk.bytes());
				body.flush();
			}
		} catch (IOException disconnected) {
			// the viewer closed the connection, which is the normal way this ends
		} finally {
			// the queue cap trips silently in offer(), and a disconnect nobody
			// can tell apart from a viewer walking away is a fault nobody finds
			if (subscriber.lagged) {
				logger.warn("Người xem luồng '" + screen + "' tụt lại quá xa"
					+ " (hàng đợi đầy); đã ngắt để họ vào lại sát thời gian thực.");
			}

			watching.remove(subscriber);
			detach(screen);
			exchange.close();
		}
	}

	/**
	 * Makes sure a screen has an encoder running, starting one if this is the
	 * first viewer to ask for it.
	 *
	 * @param screen the screen's name
	 * @return true when an encoder is running
	 */
	private boolean attach(String screen) {
		H264Encoder running = encoders.compute(key(screen), (unused, existing) -> {
			// A dead encoder in the map is worse than none: it answers attach
			// without encoding, so every new viewer waits on a stream that will
			// never carry a byte. Dying in place is normal - a power cycle stops
			// the encoder while its viewers are still connected - so absence and
			// death are treated the same and both build a fresh one.
			if (existing != null && existing.alive()) {
				return existing;
			}

			if (existing != null) {
				existing.stop();
			}

			H264Encoder built = encoderFactory.create(screen,
				chunk -> publishCoded(screen, chunk));

			if (built == null || !built.start()) {
				return null;
			}

			// A fresh encoder is fed nothing until the page next paints, and a
			// page that has settled never does. Priming it with the last frame
			// gives it something to make a keyframe out of, and seeds the pacer
			// that keeps it fed from then on.
			Stamped last = latest.get(key(screen));

			if (last != null) {
				built.prime(last.bytes());
			}

			return built;
		});

		return running != null;
	}

	/** Stops a screen's encoder once the last viewer of it has gone. */
	private void detach(String screen) {
		Set<Subscriber> watching = compressed.get(key(screen));

		if (watching != null && !watching.isEmpty()) {
			return;
		}

		H264Encoder done = encoders.remove(key(screen));

		if (done != null) {
			done.stop();
		}

		// the buffered keyframe run belongs to that encoder's stream; the next
		// one starts its own, and priming from the old one would splice two
		gops.remove(key(screen));
	}

	/**
	 * Stops a screen's encoder whatever is still watching.
	 *
	 * Called when a screen is switched off or removed: its viewers will notice
	 * the stream end and reconnect, and leaving ffmpeg attached to a browser
	 * that no longer exists helps nobody.
	 *
	 * @param screen the screen's name
	 */
	public void dropEncoder(String screen) {
		H264Encoder done = encoders.remove(key(screen));

		gops.remove(key(screen));

		if (done != null) {
			done.stop();
		}

		// The viewers go with it. A subscriber left parked would wait on a
		// stream that ended for good - the encoder they were fed by is gone -
		// while their reconnect is what triggers building the next one.
		Set<Subscriber> watching = compressed.get(key(screen));

		if (watching != null) {
			for (Subscriber subscriber : watching) {
				subscriber.done = true;
				LockSupport.unpark(subscriber.thread);
			}
		}
	}

	/**
	 * Hands one chunk of Annex-B to everyone watching a screen that way, and
	 * remembers it in case somebody else arrives.
	 *
	 * Both under one lock, and the same lock a joining viewer takes to copy the
	 * buffer. Without that a chunk can be handed out and remembered on either
	 * side of a join, and the newcomer then gets it twice - two copies of one
	 * access unit, which a decoder answers with a corrupt picture rather than a
	 * complaint.
	 */
	private void publishCoded(String screen, byte[] chunk) {
		Gop gop = gops.computeIfAbsent(key(screen), unused -> new Gop());
		Stamped block = new Stamped(chunk, nowMicros());

		synchronized (gop) {
			gop.add(chunk);

			Set<Subscriber> watching = compressed.get(key(screen));

			if (watching == null) {
				return;
			}

			for (Subscriber subscriber : watching) {
				subscriber.offer(block);
				LockSupport.unpark(subscriber.thread);
			}
		}
	}

	/** The render node encoders should use, or null for software. */
	public String vaapiDevice() {
		return vaapiDevice;
	}

	/**
	 * Whether the render node can really run the whole encode chain.
	 *
	 * A real transcode rather than a device open: the failures worth catching
	 * live deep in the driver - a JPEG surface the encoder refuses, a rate
	 * control mode the firmware lacks - and none of them show before a frame
	 * has been pushed all the way through. The probe is the exact production
	 * pipeline at stamp size, once, at startup.
	 *
	 * @param device the render node to try
	 * @return the device when the chain works, null otherwise
	 */
	private String probeVaapi(String device) {
		java.nio.file.Path sample = null;

		try {
			sample = java.nio.file.Files.createTempFile("lunatv-probe", ".mjpeg");

			if (!run(10, config.ffmpegPath(), "-hide_banner", "-loglevel", "error",
				"-f", "lavfi", "-i", "testsrc2=size=192x96:rate=30", "-frames:v", "5",
				"-c:v", "mjpeg", "-f", "mjpeg", "-y", sample.toString())) {
				return null;
			}

			boolean works = run(10, config.ffmpegPath(), "-hide_banner", "-loglevel", "error",
				"-init_hw_device", "vaapi=gpu:" + device,
				"-hwaccel", "vaapi", "-hwaccel_output_format", "vaapi",
				"-hwaccel_device", "gpu",
				"-f", "mjpeg", "-i", sample.toString(),
				"-vf", "scale_vaapi=format=nv12",
				"-c:v", "h264_vaapi", "-bf", "0", "-f", "null", "-");

			if (!works) {
				logger.warn("GPU " + device + " không mã hoá được; luồng H.264 dùng CPU.");

				return null;
			}

			return device;
		} catch (IOException | InterruptedException failed) {
			logger.warn("Không thử được GPU " + device + ": " + failed.getMessage());

			return null;
		} finally {
			if (sample != null) {
				try {
					java.nio.file.Files.deleteIfExists(sample);
				} catch (IOException ignored) {
					// a leftover probe file in tmp hurts nothing
				}
			}
		}
	}

	/** Runs one command to completion, quietly. */
	private static boolean run(int timeoutSeconds, String... command)
			throws IOException, InterruptedException {
		Process child = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();

		child.getInputStream().readAllBytes();

		if (!child.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
			child.destroyForcibly();

			return false;
		}

		return child.exitValue() == 0;
	}

	/**
	 * Whether the configured ffmpeg exists and runs.
	 *
	 * Asked once, at startup, because the answer decides what the registry tells
	 * every client and a per-request probe would be a process spawn per viewer.
	 */
	private boolean probeFfmpeg() {
		try {
			Process probe = new ProcessBuilder(config.ffmpegPath(), "-hide_banner", "-version")
				.redirectErrorStream(true)
				.start();

			probe.getInputStream().readAllBytes();

			if (!probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
				probe.destroyForcibly();

				return false;
			}

			if (probe.exitValue() != 0) {
				logger.warn("ffmpeg (" + config.ffmpegPath() + ") trả về lỗi; luồng hình dùng MJPEG.");

				return false;
			}

			return true;
		} catch (IOException | InterruptedException missing) {
			logger.warn("Không tìm thấy ffmpeg (" + config.ffmpegPath()
				+ "); luồng hình dùng MJPEG, tốn băng thông hơn nhiều.");

			return false;
		}
	}

	/**
	 * Raw PCM for one screen, for as long as the listener stays connected.
	 *
	 * Deliberately uncompressed. Encoding it would mean a codec on the client,
	 * and the whole reason the video is MJPEG is that the JDK can already
	 * decode it; audio has no such free decoder, but it also needs no decoder
	 * at all when it is already PCM. Stereo arrives interleaved so a client can
	 * split it and place the two channels at the wall's edges, which is what
	 * makes the screen sound like it is in front of you.
	 */
	private void serveAudio(HttpExchange exchange, String screen, UUID viewer) throws IOException {
		Set<Subscriber> hearing = listeners.computeIfAbsent(key(screen),
			unused -> new CopyOnWriteArraySet<>());

		if (hearing.size() >= config.streamMaxViewers()) {
			respond(exchange, 503, "too many listeners");

			return;
		}

		if (!audioTap.test(screen)) {
			respond(exchange, 409, "screen has no audio running");

			return;
		}

		Subscriber subscriber = new Subscriber();

		subscriber.player = viewer;
		hearing.add(subscriber);

		exchange.getResponseHeaders().set("Content-Type", "audio/L16; rate=48000");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);

		OutputStream body = exchange.getResponseBody();

		try {
			while (!subscriber.done && http != null) {
				Stamped block = subscriber.await(15_000L);

				if (block == null) {
					continue;
				}

				// 16-byte header per block: magic, capture time, length. Raw PCM
				// would be simpler, but then a client has no way to know when a
				// block was captured, and lining audio up with video is exactly
				// what stops the two drifting apart.
				byte[] header = new byte[16];

				writeInt(header, 0, AUDIO_MAGIC);
				writeInt(header, 4, block.bytes().length);
				writeLong(header, 8, block.pts());

				body.write(header);
				body.write(block.bytes());
				body.flush();
			}
		} catch (IOException disconnected) {
			// the listener walked away
		} finally {
			hearing.remove(subscriber);
			exchange.close();
		}
	}

	/** One multipart part carrying one frame. */
	private static void write(OutputStream body, Stamped frame) throws IOException {
		body.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\n"
			+ PTS_HEADER + ": " + frame.pts() + "\r\nContent-Length: "
			+ frame.bytes().length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(frame.bytes());
		body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
		body.flush();
	}

	/** Whether anybody is listening to a screen. */
	public boolean heard(String screen) {
		Set<Subscriber> hearing = listeners.get(key(screen));

		return hearing != null && !hearing.isEmpty();
	}

	/**
	 * Whether one player is taking a screen's sound off this server.
	 *
	 * Asked per voice-chat packet, so it stays a set lookup: the connection
	 * itself is the record, which means nothing has to be told when somebody
	 * walks away and their socket closes.
	 *
	 * @param screen the screen's name
	 * @param player who to ask about
	 * @return true when that player has the audio stream open
	 */
	public boolean hearing(String screen, UUID player) {
		Set<Subscriber> listening = listeners.get(key(screen));

		if (listening == null) {
			return false;
		}

		for (Subscriber subscriber : listening) {
			if (player.equals(subscriber.player)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Who the request's token was issued to.
	 *
	 * The identity, not just a yes: an audio listener has to be recognisable
	 * later, because that is what tells voice chat to stop sending the same
	 * screen's sound to somebody already receiving it here.
	 *
	 * @param exchange the request
	 * @return the player, or null when the token is missing or expired
	 */
	private UUID grantee(HttpExchange exchange) {
		String query = exchange.getRequestURI().getQuery();

		if (query == null) {
			return null;
		}

		String token = null;

		for (String part : query.split("&")) {
			if (part.startsWith("t=")) {
				token = part.substring(2);
			}
		}

		if (token == null) {
			return null;
		}

		Grant grant = tokens.get(token);

		if (grant == null) {
			return null;
		}

		if (grant.expiresAt < System.currentTimeMillis()) {
			tokens.remove(token);

			return null;
		}

		return grant.player;
	}

	private static void writeInt(byte[] into, int at, int value) {
		into[at] = (byte) (value >>> 24);
		into[at + 1] = (byte) (value >>> 16);
		into[at + 2] = (byte) (value >>> 8);
		into[at + 3] = (byte) value;
	}

	private static void writeLong(byte[] into, int at, long value) {
		writeInt(into, at, (int) (value >>> 32));
		writeInt(into, at + 4, (int) value);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static String key(String screen) {
		return screen.toLowerCase(java.util.Locale.ROOT);
	}

	/** One watching or listening connection. */
	private static final class Subscriber {

		/**
		 * Audio blocks waiting to be written.
		 *
		 * Video is newest-wins because a stale frame is worthless - the next one
		 * replaces it and nobody can tell. Audio is the opposite: every block is
		 * 20ms of sound, and a dropped one is an audible click, so it queues
		 * instead. Measured before this existed, half of every second's audio was
		 * being discarded by the single-slot handoff.
		 */
		private final java.util.concurrent.ConcurrentLinkedQueue<Stamped> queued =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

		/** Blocks allowed to back up before the oldest are dropped: ~1 second. */
		private static final int MAX_QUEUED = 50;

		/**
		 * Queued chunks past which a coded viewer is skipped to a keyframe.
		 *
		 * About a second of stream at the default bitrate. A viewer whose
		 * decoder runs a few frames short of the stream rate builds lag at the
		 * difference, and before this existed the only remedy was the
		 * disconnect below: a twenty-second freeze every few minutes, measured
		 * on a machine decoding 55 of a 60fps stream. Skipping the queue to
		 * the newest keyframe run costs them the pictures they were already
		 * late for and nothing else.
		 */
		private static final int SHED_QUEUED = 12;

		/** Chunks discarded to keep this viewer near live, for the log. */
		private final AtomicLong shed = new AtomicLong();

		private final java.util.concurrent.atomic.AtomicInteger depth =
			new java.util.concurrent.atomic.AtomicInteger();

		private final AtomicReference<Stamped> pending = new AtomicReference<>();
		private final Thread thread = Thread.currentThread();

		/** Who is on the other end; only audio listeners need to be recognised. */
		private volatile UUID player;

		private volatile boolean done;

		/**
		 * Whether losing a payload corrupts the stream rather than costing one
		 * frame of it. True for H.264, where every frame is described against
		 * the ones before it.
		 */
		private volatile boolean lossless;

		/** Set when the queue cap ended this connection, so the end is named. */
		private volatile boolean lagged;

		/** When this connection last wrote, for frame-rate pacing. */
		private long lastWriteNanos;

		/** Bytes written inside the current budget window. */
		private long windowBytes;
		private long windowStartNanos = System.nanoTime();

		/**
		 * Waits for the next frame.
		 *
		 * @param timeoutMs how long to wait before giving up
		 * @return the newest frame, or null when none arrived in time
		 */
		/**
		 * Queues an ordered payload, dropping the oldest when far behind.
		 *
		 * A listener that has stopped reading must not grow this without bound,
		 * but the cap is deliberately about a second: trimming sooner would turn
		 * ordinary jitter into the very gaps the queue exists to prevent.
		 *
		 * @param block the payload to deliver in order
		 */
		private void offer(Stamped block) {
			queued.add(block);
			depth.incrementAndGet();

			// A coded stream cannot lose an arbitrary chunk: everything after
			// it is broken until the next keyframe. It CAN be spliced onto a
			// keyframe run, which is exactly how a joiner starts mid-stream, so
			// a viewer falling behind is skipped forward to the newest one in
			// the queue. The disconnect stays as the backstop for a queue that
			// somehow holds no keyframe at all.
			if (lossless) {
				if (depth.get() > SHED_QUEUED) {
					shedToKeyframe();
				}

				if (depth.get() > MAX_QUEUED) {
					lagged = true;
					done = true;
				}

				return;
			}

			while (depth.get() > MAX_QUEUED) {
				if (queued.poll() == null) {
					break;
				}

				depth.decrementAndGet();
			}
		}

		/**
		 * Drops everything queued before the newest keyframe run.
		 *
		 * The landing chunk is the last one carrying a parameter set: with
		 * repeat-headers every keyframe is preceded by its SPS, so decoding
		 * resumes cleanly there, and any partial access unit at the front of
		 * that chunk is skipped by the decoder's own start-code scan. Racing
		 * the consumer is harmless: both ends only ever remove from the head.
		 */
		private void shedToKeyframe() {
			Stamped landing = null;

			for (Stamped chunk : queued) {
				if (Gop.carriesParameterSet(chunk.bytes())) {
					landing = chunk;
				}
			}

			if (landing == null || queued.peek() == landing) {
				return;
			}

			while (true) {
				Stamped head = queued.peek();

				if (head == null || head == landing) {
					break;
				}

				if (queued.poll() != null) {
					depth.decrementAndGet();
					shed.incrementAndGet();
				}
			}
		}

		private Stamped await(long timeoutMs) {
			long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

			while (!done) {
				Stamped ordered = queued.poll();

				if (ordered != null) {
					depth.decrementAndGet();

					return ordered;
				}

				Stamped frame = pending.getAndSet(null);

				if (frame != null) {
					return frame;
				}

				long left = deadline - System.nanoTime();

				if (left <= 0) {
					return null;
				}

				LockSupport.parkNanos(Math.min(left, WRITE_PARK_NANOS * 50));
			}

			return null;
		}

		/**
		 * Whether this connection may be written to now.
		 *
		 * Two limits, both per connection: a minimum gap between frames, and a
		 * byte budget over a rolling second. A frame that fits neither is
		 * dropped rather than queued, because the next one supersedes it.
		 *
		 * @param minGapNanos shortest allowed spacing between writes
		 * @param size bytes about to be written
		 * @param megabits ceiling in megabits per second, 0 for none
		 * @return true when the frame should be written
		 */
		private boolean due(long minGapNanos, int size, int megabits) {
			long now = System.nanoTime();

			if (now - lastWriteNanos < minGapNanos) {
				return false;
			}

			if (megabits > 0) {
				if (now - windowStartNanos >= 1_000_000_000L) {
					windowStartNanos = now;
					windowBytes = 0;
				}

				if (windowBytes + size > megabits * 1_000_000L / 8) {
					return false;
				}

				windowBytes += size;
			}

			lastWriteNanos = now;

			return true;
		}
	}

	/**
	 * The coded bytes since a screen's last keyframe.
	 *
	 * Reset whenever a chunk carries a sequence parameter set, which with
	 * repeat-headers is exactly the run-up to every keyframe. The bytes before
	 * the parameter set in that chunk are kept rather than trimmed away: they
	 * are the tail of the previous run, a decoder skips them until it finds
	 * something it can start on, and finding the boundary exactly would mean
	 * carrying partial start codes between chunks for no gain.
	 */
	private static final class Gop {

		private final List<byte[]> chunks = new ArrayList<>();

		private int bytes;

		private void add(byte[] chunk) {
			if (carriesParameterSet(chunk)) {
				chunks.clear();
				bytes = 0;
			}

			if (bytes + chunk.length > MAX_GOP_BYTES) {
				chunks.clear();
				bytes = 0;

				return;
			}

			chunks.add(chunk);
			bytes += chunk.length;
		}

		private List<byte[]> copy() {
			return List.copyOf(chunks);
		}

		/** Whether a chunk contains an Annex-B start code introducing an SPS. */
		private static boolean carriesParameterSet(byte[] chunk) {
			for (int at = 0; at + 3 < chunk.length; at++) {
				if (chunk[at] != 0 || chunk[at + 1] != 0) {
					continue;
				}

				int header;

				if (chunk[at + 2] == 1) {
					header = chunk[at + 3];
				} else if (chunk[at + 2] == 0 && at + 4 < chunk.length && chunk[at + 3] == 1) {
					header = chunk[at + 4];
				} else {
					continue;
				}

				// nal_unit_type 7 is the sequence parameter set
				if ((header & 0x1F) == 7) {
					return true;
				}
			}

			return false;
		}
	}

	/** Builds a screen's encoder. Implemented by the plugin, called from here. */
	public interface EncoderFactory {

		/**
		 * @param screen the screen's name
		 * @param sink receives each chunk of Annex-B the encoder produces
		 * @return the encoder, not yet started, or null when the screen is gone
		 */
		H264Encoder create(String screen, java.util.function.Consumer<byte[]> sink);
	}

	private record Grant(UUID player, long expiresAt) {
	}

	/** A payload and the moment it was captured. */
	private record Stamped(byte[] bytes, long pts) {
	}
}
