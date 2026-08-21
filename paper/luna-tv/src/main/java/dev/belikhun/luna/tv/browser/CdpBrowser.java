package dev.belikhun.luna.tv.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.DataBufferByte;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;

/**
 * A browser a screen can drive: one Chromium process, one CDP connection, and
 * the newest decoded frame.
 *
 * Frames are published newest-wins through an {@link AtomicReference} rather
 * than queued. A map wall shows the present, so a backlog is worthless: if the
 * render thread is late, the right behaviour is to skip to the latest frame,
 * not to fall further behind replaying stale ones.
 *
 * ImageIO decodes the JPEG. It needs no display (headless is fine) and is part
 * of the JDK, which is why this backend ships no native libraries at all.
 */
public final class CdpBrowser implements AutoCloseable {

	private final LunaLogger logger;
	private final TvConfig config;
	private final ChromiumProcess process;
	private final CdpConnection cdp;
	private final int width;
	private final int height;
	private volatile int captureWidth;
	private volatile int captureHeight;

	/**
	 * Frame decoding is done here, never on the WebSocket thread.
	 *
	 * Decoding a screencast frame is JPEG decompression plus a full-screen
	 * upscale; doing that inline on the HTTP client's reader thread pinned a
	 * whole core and stalled every other CDP reply behind it.
	 */
	private final ExecutorService decoder;
	private final AtomicReference<String> pending = new AtomicReference<>();

	private ImageReader jpegReader;
	private BufferedImage decodeTarget;
	private int[] columnIndex;
	private int columnIndexFor;
	private final FrameBuffers buffers;
	private final AtomicReference<BrowserFrame> latest = new AtomicReference<>();
	private final AtomicLong decoded = new AtomicLong();

	private volatile boolean closed;
	private volatile String currentUrl;
	private volatile long minFrameIntervalNanos;
	private volatile long nextAckAt;
	private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

	private CdpBrowser(
		LunaLogger logger,
		TvConfig config,
		ChromiumProcess process,
		CdpConnection cdp,
		int width,
		int height,
		int scale,
		String url
	) {
		this.logger = logger;
		this.config = config;
		this.process = process;
		this.cdp = cdp;
		this.width = width;
		this.height = height;
		this.captureWidth = Math.max(1, width / scale);
		this.captureHeight = Math.max(1, height / scale);
		this.buffers = new FrameBuffers(width * height);
		this.decoder = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "LunaTv-Decode-" + process.port());

			thread.setDaemon(true);

			return thread;
		});
		this.currentUrl = url;
		this.minFrameIntervalNanos = 1_000_000_000L / Math.max(1, config.fps());
	}

	/**
	 * Starts a browser for one screen and begins streaming frames.
	 *
	 * @param logger scoped logger
	 * @param config plugin configuration
	 * @param profileDir private Chromium profile directory for this screen
	 * @param sinkName PulseAudio sink this browser's audio must reach, or null
	 * @param width viewport width in pixels
	 * @param height viewport height in pixels
	 * @param url first page to open
	 * @param onDeath called when the browser or its socket dies
	 * @return a running browser
	 * @throws Exception if Chromium or the CDP handshake fails
	 */
	public static CdpBrowser start(
		LunaLogger logger,
		TvConfig config,
		Path profileDir,
		String sinkName,
		int width,
		int height,
		int scale,
		String url,
		Consumer<Throwable> onDeath
	) throws Exception {
		// launched on about:blank so the first real page is only requested after
		// the user agent is fixed below; YouTube answers a HeadlessChrome UA with
		// "Something went wrong" instead of video
		ChromiumProcess process = ChromiumProcess.launch(config, profileDir, sinkName, width, height, "about:blank");
		CdpConnection cdp;

		try {
			cdp = CdpConnection.open(process.websocketUrl());
		} catch (Exception exception) {
			process.stop();

			throw exception;
		}

		CdpBrowser browser = new CdpBrowser(logger, config, process, cdp, width, height,
			scale, url);

		cdp.onFailure(throwable -> {
			if (browser.closed) {
				return;
			}

			onDeath.accept(throwable);
		});

		browser.begin();

		return browser;
	}

	private void begin() {
		cdp.on("Page.screencastFrame", this::onScreencastFrame);
		cdp.call("Page.enable", Map.of());
		cdp.call("Runtime.enable", Map.of());

		fixUserAgent();

		// The page is laid out at the CAPTURE size, not the wall size. This is the
		// difference between a video site software-decoding 1080p and 480p: sites
		// pick their stream for the viewport they see, and the wall's palette
		// cannot show the difference anyway. It also fixes the shorter-viewport
		// problem (--window-size includes browser chrome).
		cdp.call("Emulation.setDeviceMetricsOverride", Map.of(
			"width", captureWidth,
			"height", captureHeight,
			"deviceScaleFactor", 1,
			"mobile", false));

		startScreencast();

		// only now is the real page requested, with the corrected user agent
		cdp.call("Page.navigate", Map.of("url", currentUrl));
	}

	/**
	 * Applies a new capture divisor to a running browser.
	 *
	 * The page is re-laid-out at the new viewport and the screencast restarted
	 * at the matching size; the page itself keeps playing. A frame captured at
	 * the old size can still arrive mid-switch - it upscales against the new
	 * factor once and is corrected by the next frame.
	 *
	 * @param scale the divisor, 1..4
	 */
	public void rescale(int scale) {
		int safe = Math.max(1, Math.min(4, scale));

		captureWidth = Math.max(1, width / safe);
		captureHeight = Math.max(1, height / safe);
		columnIndex = null;

		cdp.call("Emulation.setDeviceMetricsOverride", Map.of(
			"width", captureWidth,
			"height", captureHeight,
			"deviceScaleFactor", 1,
			"mobile", false));
		cdp.call("Page.stopScreencast", Map.of());
		startScreencast();
	}

	/**
	 * Presents the browser as an ordinary desktop Chrome.
	 *
	 * Headless Chromium announces itself as "HeadlessChrome", and sites treat
	 * that as a bot: YouTube in particular refuses to start video playback for
	 * it. The identity to present comes from browser.user-agent in config.yml;
	 * an empty value falls back to the browser's own string with only the
	 * product name corrected.
	 *
	 * Waited on rather than fired off, because the navigate that follows must
	 * not race it: begin() runs on an async thread, never the server thread.
	 */
	private void fixUserAgent() {
		try {
			String agent = config.userAgent();

			if (agent == null || agent.isBlank()) {
				JsonObject version = cdp.call("Browser.getVersion", Map.of())
					.get(5, java.util.concurrent.TimeUnit.SECONDS);

				if (!version.has("userAgent")) {
					return;
				}

				agent = version.get("userAgent").getAsString().replace("HeadlessChrome", "Chrome");
			}

			cdp.call("Emulation.setUserAgentOverride", Map.of("userAgent", agent))
				.get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception exception) {
			logger.warn("Không sửa được user agent: " + exception.getMessage());
		}
	}

	private void startScreencast() {
		cdp.call("Page.startScreencast", Map.of(
			"format", "jpeg",
			"quality", config.quality(),
			"maxWidth", captureWidth,
			"maxHeight", captureHeight,
			"everyNthFrame", 1
		));
	}

	private void onScreencastFrame(JsonObject params) {
		// The ack is the throttle. Chromium will not capture the next frame until
		// the last one is acknowledged, so pacing the acks to the display rate is
		// what keeps its compositor from spinning flat out producing frames the
		// wall can never show - on a video page that was worth several whole
		// cores. Dropping frames after arrival throttles nothing: the expensive
		// part already happened inside Chromium by then.
		if (params.has("sessionId")) {
			int sessionId = params.get("sessionId").getAsInt();
			long now = System.nanoTime();
			long at = Math.max(now, nextAckAt);
			long wait = at - now;

			nextAckAt = at + minFrameIntervalNanos;

			if (wait <= 0) {
				cdp.send("Page.screencastFrameAck", Map.of("sessionId", sessionId));
			} else {
				java.util.concurrent.CompletableFuture
					.delayedExecutor(wait, java.util.concurrent.TimeUnit.NANOSECONDS)
					.execute(() -> {
						if (!closed) {
							cdp.send("Page.screencastFrameAck", Map.of("sessionId", sessionId));
						}
					});
			}
		}

		if (closed || !params.has("data")) {
			return;
		}

		// newest wins: a frame still queued when the next arrives is worthless,
		// and dropping it here costs nothing because nothing has been decoded yet
		pending.set(params.get("data").getAsString());

		try {
			decoder.execute(this::drainPending);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// shutting down
		}
	}

	private void drainPending() {
		String data = pending.getAndSet(null);

		if (data == null || closed) {
			return;
		}

		try {
			BufferedImage image = decodeJpeg(Base64.getDecoder().decode(data));

			if (image != null) {
				publish(image);
			}
		} catch (Throwable throwable) {
			if (config.debug()) {
				logger.debug("Không giải mã được khung hình: " + throwable);
			}
		}
	}

	/**
	 * Decodes a JPEG frame into a reused INT_RGB image.
	 *
	 * ImageIO.read would build a reader, a stream and a fresh image every
	 * frame, and hand back a 3BYTE_BGR raster whose getRGB costs a colour
	 * conversion per pixel. Decoding straight into an int-backed destination
	 * lets the pixels be read as a plain array afterwards.
	 *
	 * @param jpeg the compressed frame
	 * @return the decoded image, or null when the frame was unreadable
	 */
	private BufferedImage decodeJpeg(byte[] jpeg) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
			if (jpegReader == null) {
				java.util.Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");

				if (!readers.hasNext()) {
					return null;
				}

				jpegReader = readers.next();
			}

			jpegReader.setInput(input, true, true);

			int frameWidth = jpegReader.getWidth(0);
			int frameHeight = jpegReader.getHeight(0);

			// 3BYTE_BGR is what the JPEG decoder produces natively. Asking for
			// INT_RGB instead makes it colour-convert every pixel on the way out,
			// which measured ~7ms/frame slower than converting ourselves later.
			if (decodeTarget == null
					|| decodeTarget.getWidth() != frameWidth
					|| decodeTarget.getHeight() != frameHeight) {
				decodeTarget = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_3BYTE_BGR);
			}

			ImageReadParam param = jpegReader.getDefaultReadParam();

			param.setDestination(decodeTarget);

			return jpegReader.read(0, param);
		} finally {
			if (jpegReader != null) {
				jpegReader.setInput(null);
			}
		}
	}

	private void publish(BufferedImage image) {
		// Read straight out of the decoder's own BGR raster and fold the colour
		// conversion into the scaling pass, so the pixels are only walked once.
		byte[] source = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		int[] pixels = buffers.take();
		int frameWidth = Math.min(width, sourceWidth * width / Math.max(1, captureWidth));
		int frameHeight = Math.min(height, sourceHeight * height / Math.max(1, captureHeight));
		int[] columns = columns(sourceWidth);
		int lastSourceRow = -1;
		int lastDestinationRow = 0;

		for (int y = 0; y < frameHeight; y++) {
			int sourceRow = Math.min(sourceHeight - 1, y * captureHeight / height);
			int destinationRow = y * width;

			// a source row repeated across several destination rows is copied
			// wholesale rather than rebuilt pixel by pixel
			if (sourceRow == lastSourceRow) {
				System.arraycopy(pixels, lastDestinationRow, pixels, destinationRow, frameWidth);

				continue;
			}

			int base = sourceRow * sourceWidth;

			for (int x = 0; x < frameWidth; x++) {
				int at = (base + columns[x]) * 3;

				// the palette treats a transparent pixel as a hole, so alpha is
				// forced opaque here rather than trusted from the decoder
				pixels[destinationRow + x] = 0xFF000000
					| ((source[at + 2] & 0xFF) << 16)
					| ((source[at + 1] & 0xFF) << 8)
					| (source[at] & 0xFF);
			}

			lastSourceRow = sourceRow;
			lastDestinationRow = destinationRow;
		}

		decoded.incrementAndGet();
		TvDebug.sampled("decode:" + debugPort(), 100,
			"decode port=" + debugPort() + " " + frameWidth + "x" + frameHeight
			+ " (viewport " + width + "x" + height + ")");

		BrowserFrame previous = latest.getAndSet(new BrowserFrame(pixels, frameWidth, frameHeight, width));

		// whatever the render thread never collected goes straight back to the pool
		if (previous != null) {
			buffers.recycle(previous.pixels());
		}
	}

	/**
	 * Takes the newest frame, if one arrived since the last call.
	 *
	 * @return the frame, or null when nothing new has been painted
	 */
	public BrowserFrame poll() {
		return latest.getAndSet(null);
	}

	/**
	 * Hands a consumed frame's array back for reuse.
	 *
	 * @param frame a frame previously returned by {@link #poll()}
	 */
	/**
	 * The source column each destination column samples, cached.
	 *
	 * Recomputed only when the capture size changes; without it every pixel of
	 * every frame pays for a multiply and a divide.
	 *
	 * @param sourceWidth width of the decoded frame
	 * @return one source index per destination column
	 */
	private int[] columns(int sourceWidth) {
		if (columnIndex != null && columnIndexFor == sourceWidth) {
			return columnIndex;
		}

		int[] table = new int[width];

		for (int x = 0; x < width; x++) {
			table[x] = Math.min(sourceWidth - 1, x * captureWidth / width);
		}

		columnIndex = table;
		columnIndexFor = sourceWidth;

		return table;
	}

	public void recycle(BrowserFrame frame) {
		if (frame == null) {
			return;
		}

		buffers.recycle(frame.pixels());
	}

	/**
	 * Navigates to a page.
	 *
	 * @param url the address to open
	 */
	public void navigate(String url) {
		currentUrl = url;
		TvDebug.log("navigate port=" + debugPort() + " url=" + url);
		cdp.call("Page.navigate", Map.of("url", url))
			.exceptionally(throwable -> {
				logger.warn("Không mở được trang: " + throwable.getMessage());

				return null;
			});
	}

	public void back() {
		history(-1);
	}

	public void forward() {
		history(1);
	}

	/**
	 * Steps through session history.
	 *
	 * CDP has no "go back": it exposes the entry list and a jump, so the step
	 * is resolved against the live history rather than assumed.
	 *
	 * @param delta -1 for back, 1 for forward
	 */
	private void history(int delta) {
		cdp.call("Page.getNavigationHistory", Map.of()).thenAccept(result -> {
			if (!result.has("currentIndex") || !result.has("entries")) {
				return;
			}

			int index = result.get("currentIndex").getAsInt() + delta;

			if (index < 0 || index >= result.getAsJsonArray("entries").size()) {
				return;
			}

			int id = result.getAsJsonArray("entries").get(index).getAsJsonObject().get("id").getAsInt();
			cdp.call("Page.navigateToHistoryEntry", Map.of("entryId", id));
		});
	}

	public void reload() {
		cdp.call("Page.reload", Map.of("ignoreCache", false));
	}

	/**
	 * Clicks at a point in browser pixel space.
	 *
	 * A click is a press plus a release: pages that only listen for one of the
	 * two would otherwise see half a gesture.
	 *
	 * @param x horizontal pixel, browser space
	 * @param y vertical pixel, browser space
	 * @param right true for the secondary button
	 */
	public void click(int x, int y, boolean right) {
		// callers work in wall pixels; the page lives at capture size
		x = x * captureWidth / width;
		y = y * captureHeight / height;
		TvDebug.log("click port=" + debugPort() + " at=" + x + "," + y + " (page space) right=" + right);

		String button = right ? "right" : "left";
		int buttons = right ? 2 : 1;

		// a move first, so hover-driven UIs (menus, buttons) see the pointer arrive
		cdp.send("Input.dispatchMouseEvent", Map.of(
			"type", "mouseMoved", "x", x, "y", y, "button", "none", "buttons", 0));
		cdp.send("Input.dispatchMouseEvent", Map.of(
			"type", "mousePressed", "x", x, "y", y, "button", button, "buttons", buttons, "clickCount", 1));
		cdp.send("Input.dispatchMouseEvent", Map.of(
			"type", "mouseReleased", "x", x, "y", y, "button", button, "buttons", 0, "clickCount", 1));
	}

	/**
	 * Scrolls at a point.
	 *
	 * @param x horizontal pixel, browser space
	 * @param y vertical pixel, browser space
	 * @param deltaY pixels to scroll, positive scrolls down
	 */
	public void scroll(int x, int y, int deltaY) {
		cdp.send("Input.dispatchMouseEvent", Map.of(
			"type", "mouseWheel", "x", x * captureWidth / width, "y", y * captureHeight / height,
			"button", "none", "deltaX", 0, "deltaY", deltaY));
	}

	/**
	 * Types text into the focused element.
	 *
	 * @param text the characters to insert
	 */
	public void type(String text) {
		cdp.call("Input.insertText", Map.of("text", text));
	}

	/**
	 * Sends one named key.
	 *
	 * @param key one of enter, backspace, tab, escape
	 */
	public void key(String key) {
		KeySpec spec = KeySpec.of(key);

		if (spec == null) {
			return;
		}

		cdp.send("Input.dispatchKeyEvent", Map.of(
			"type", "keyDown", "key", spec.key(), "code", spec.code(),
			"windowsVirtualKeyCode", spec.code0(), "nativeVirtualKeyCode", spec.code0(),
			"text", spec.text()));
		cdp.send("Input.dispatchKeyEvent", Map.of(
			"type", "keyUp", "key", spec.key(), "code", spec.code(),
			"windowsVirtualKeyCode", spec.code0(), "nativeVirtualKeyCode", spec.code0()));
	}

	/**
	 * Mutes or unmutes every media element on the page.
	 *
	 * CDP has no audio-mute command, so this is done in the page: existing
	 * elements are set now and a MutationObserver catches the ones a video site
	 * creates later. Kept for the per-screen volume control; the authoritative
	 * mute for a whole screen is not playing its audio channel at all.
	 *
	 * @param muted true to silence the page
	 */
	public void setMuted(boolean muted) {
		String script = """
			(() => {
				window.__lunatvMuted = %s;
				const apply = () => document.querySelectorAll('video,audio')
					.forEach(el => { el.muted = window.__lunatvMuted; });
				apply();
				if (!window.__lunatvObserver) {
					window.__lunatvObserver = new MutationObserver(apply);
					window.__lunatvObserver.observe(document.documentElement, { childList: true, subtree: true });
				}
			})();
			""".formatted(muted ? "true" : "false");

		cdp.call("Runtime.evaluate", Map.of("expression", script, "awaitPromise", false));
	}

	/**
	 * Runs a script in the page, fire and forget.
	 *
	 * The control panel's play/pause and seek land here: media state lives in
	 * the page, and the page is the only one who knows it.
	 *
	 * @param script the JavaScript to run
	 */
	public void evaluate(String script) {
		cdp.call("Runtime.evaluate", Map.of("expression", script, "awaitPromise", false));
	}

	public String currentUrl() {
		return currentUrl;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean alive() {
		return !closed && process.alive();
	}

	public long pid() {
		return process.pid();
	}

	public int debugPort() {
		return process.port();
	}

	/**
	 * Frames decoded since the browser started.
	 *
	 * Counted because a blank wall has two very different causes - the browser is
	 * not painting, or the display is not being sent - and this separates them.
	 *
	 * @return the running total
	 */
	public long framesDecoded() {
		return decoded.get();
	}

	/**
	 * Frames dropped before decoding because they arrived faster than the wall
	 * can show them. A large number here is normal and is the throttle working.
	 *
	 * @return the running total
	 */
	public long framesDropped() {
		return dropped.get();
	}

	/**
	 * Re-reads the display rate, so a config reload takes effect without
	 * restarting the browser.
	 *
	 * @param fps frames per second the wall will show
	 */
	public void displayRate(int fps) {
		this.minFrameIntervalNanos = 1_000_000_000L / Math.max(1, fps);
	}

	@Override
	public void close() {
		closed = true;
		decoder.shutdownNow();

		if (jpegReader != null) {
			jpegReader.dispose();
			jpegReader = null;
		}

		try {
			cdp.close();
		} finally {
			process.stop();
		}
	}

	/** The keys the controllers offer, in CDP's own vocabulary. */
	private record KeySpec(String key, String code, int code0, String text) {

		static KeySpec of(String name) {
			return switch (name.toLowerCase(java.util.Locale.ROOT)) {
				case "enter" -> new KeySpec("Enter", "Enter", 13, "\r");
				case "backspace" -> new KeySpec("Backspace", "Backspace", 8, "");
				case "tab" -> new KeySpec("Tab", "Tab", 9, "\t");
				case "escape", "esc" -> new KeySpec("Escape", "Escape", 27, "");
				case "up", "arrowup" -> new KeySpec("ArrowUp", "ArrowUp", 38, "");
				case "down", "arrowdown" -> new KeySpec("ArrowDown", "ArrowDown", 40, "");
				case "left", "arrowleft" -> new KeySpec("ArrowLeft", "ArrowLeft", 37, "");
				case "right", "arrowright" -> new KeySpec("ArrowRight", "ArrowRight", 39, "");
				case "space" -> new KeySpec(" ", "Space", 32, " ");
				case "home" -> new KeySpec("Home", "Home", 36, "");
				case "end" -> new KeySpec("End", "End", 35, "");
				case "pageup" -> new KeySpec("PageUp", "PageUp", 33, "");
				case "pagedown" -> new KeySpec("PageDown", "PageDown", 34, "");
				default -> null;
			};
		}
	}
}
