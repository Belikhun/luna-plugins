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
	/**
	 * Injected into every document: nothing may open a second tab, and the
	 * attempt is dropped rather than redirected.
	 *
	 * A wall is a shared surface, so an unattended page must not be able to
	 * move itself somewhere else - an advertisement calling window.open would
	 * otherwise navigate the whole screen. Every route to a new window is made
	 * inert: window.open hands back a stub that goes nowhere, and a click on a
	 * target="_blank" link is cancelled outright.
	 *
	 * Fullscreen is emulated rather than allowed. Real fullscreen keeps the
	 * viewport and the metrics override unchanged but makes Chromium deliver
	 * screencast frames at a different, non-uniformly scaled size (512x320
	 * requested, 800x544 delivered), which cannot be mapped back onto the wall
	 * without distorting and cropping it. A wall is already a whole screen, so
	 * the element is stretched over the viewport instead and the page is told it
	 * worked, which keeps its own controls in the right state.
	 */
	/** How often the capture surface may be forced back into shape. */
	private static final long SURFACE_FIX_INTERVAL_MS = 3_000L;

	/**
	 * Ordered dithering perturbs each pixel by a fixed, position-dependent
	 * offset before MapEngine's nearest-colour lookup. Unlike Floyd-Steinberg
	 * there is no error carried between pixels, so it costs a few adds and
	 * table reads per pixel inside a loop that already runs, instead of a
	 * ForkJoinPool pass over the whole frame (measured at ~1.5 cores on a
	 * 12x6 wall). Every pattern is static per position, so a still page
	 * dithers identically every frame and MapEngine's per-player deltas stay
	 * empty.
	 *
	 * Two families are offered: the classic 8x8 Bayer matrix, and the four
	 * "a dither" masks from https://pippin.gimp.org/a_dither/ - arithmetic on
	 * (x, y) with magic constants, whose noise reads as texture rather than
	 * Bayer's crosshatch grid. The per-channel variants (a2, a4) shift each
	 * colour channel's mask, trading a little chroma noise for finer detail.
	 */
	private static final int DITHER_OFF = 0;
	private static final int DITHER_BAYER = 1;
	private static final int DITHER_A_XOR = 2;
	private static final int DITHER_A_XOR_CHANNEL = 3;
	private static final int DITHER_A_ADD = 4;
	private static final int DITHER_A_ADD_CHANNEL = 5;

	/** The classic 8x8 Bayer threshold matrix, values 0..63. */
	private static final int[][] BAYER = {
		{  0, 32,  8, 40,  2, 34, 10, 42 },
		{ 48, 16, 56, 24, 50, 18, 58, 26 },
		{ 12, 44,  4, 36, 14, 46,  6, 38 },
		{ 60, 28, 52, 20, 62, 30, 54, 22 },
		{  3, 35, 11, 43,  1, 33,  9, 41 },
		{ 51, 19, 59, 27, 49, 17, 57, 25 },
		{ 15, 47,  7, 39, 13, 45,  5, 37 },
		{ 63, 31, 55, 23, 61, 29, 53, 21 },
	};

	/**
	 * Peak-to-peak amplitude of the dither offsets, in channel values.
	 *
	 * Sized to the map palette's own spacing: adjacent shades of one base
	 * colour sit a factor of 255/220 apart, roughly 14..27 values across the
	 * midtones, so a swing of about 32 lets the quantiser alternate between
	 * two neighbouring palette colours without washing detail out.
	 */
	private static final int DITHER_SPREAD = 32;

	/** Headroom the clamp table leaves on each side of 0..255. */
	private static final int DITHER_PAD = 32;

	/** clamp(i - DITHER_PAD, 0, 255), so the dither loop never branches. */
	private static final int[] DITHER_CLAMP = new int[256 + DITHER_PAD * 2];

	/** Straight-through tone curve, so the dither loop needs no null check. */
	private static final int[] IDENTITY_TONE = new int[256];

	/** Bayer thresholds mapped to pre-biased offsets, so row fills only read. */
	private static final int[][] BAYER_OFFSETS = new int[8][8];

	/** 9-bit a-dither mask value to pre-biased offset (the xor patterns). */
	private static final int[] DITHER_OFF_511 = new int[512];

	/** 8-bit a-dither mask value to pre-biased offset (the add patterns). */
	private static final int[] DITHER_OFF_255 = new int[256];

	static {
		for (int index = 0; index < DITHER_CLAMP.length; index++) {
			DITHER_CLAMP[index] = Math.max(0, Math.min(255, index - DITHER_PAD));
		}

		for (int value = 0; value < 256; value++) {
			IDENTITY_TONE[value] = value;
		}

		// each mask value t out of n maps onto a symmetric swing:
		// offset = spread * ((t + 0.5) / n - 0.5), pre-biased by the pad
		for (int row = 0; row < 8; row++) {
			for (int column = 0; column < 8; column++) {
				BAYER_OFFSETS[row][column] = DITHER_PAD
					+ (DITHER_SPREAD * (2 * BAYER[row][column] + 1 - 64)) / 128;
			}
		}

		for (int t = 0; t < 512; t++) {
			DITHER_OFF_511[t] = DITHER_PAD + (DITHER_SPREAD * (2 * t + 1 - 512)) / 1024;
		}

		for (int t = 0; t < 256; t++) {
			DITHER_OFF_255[t] = DITHER_PAD + (DITHER_SPREAD * (2 * t + 1 - 256)) / 512;
		}
	}

	private static final String SAME_TAB_SCRIPT = """
		(() => {
			if (window.__lunaTvNoPopups) { return; }
			window.__lunaTvNoPopups = true;

			const inert = () => ({
				closed: true,
				focus() {}, blur() {}, close() {}, postMessage() {},
				document: { write() {}, writeln() {}, close() {} },
				get location() { return { href: '', assign() {}, replace() {} }; },
				set location(ignored) {}
			});

			window.open = function () { return inert(); };

			// capture phase, so the default action never happens
			const block = (event) => {
				const node = event.target;

				if (!node || !node.closest) { return; }

				const hit = node.closest('a[target], form[target]');

				if (!hit || !hit.target || hit.target === '_self') { return; }

				event.preventDefault();
				event.stopPropagation();
			};

			document.addEventListener('click', block, true);
			document.addEventListener('auxclick', block, true);
			document.addEventListener('submit', block, true);

			// Leaving a page while it is playing wedges the renderer outright in
			// this headless build: the process stays up and the browser process
			// keeps answering, but the page itself stops for good and only a
			// relaunch clears it. Pausing first is measured to avoid it, so every
			// route out of a playing document pauses on the way.
			const pauseMedia = () => {
				try {
					document.querySelectorAll('video, audio').forEach((media) => {
						try { media.pause(); } catch (ignored) {}
					});
				} catch (ignored) {}
			};

			// Only for a link that actually leaves this origin: an in-page link is
			// the site's own navigation, and pausing there would interrupt a song
			// every time somebody picked the next one.
			//
			// The navigation is taken over rather than merely preceded by a pause.
			// Pausing in the same tick is not enough - the media pipeline is still
			// tearing down when the navigation commits, which is the exact race
			// that wedges the renderer - so the click is cancelled, the media
			// stopped, and the navigation re-issued once that has settled.
			document.addEventListener('click', (event) => {
				const node = event.target;

				if (!node || !node.closest) { return; }

				const link = node.closest('a[href]');

				if (!link) { return; }

				let to;

				try {
					to = new URL(link.href, location.href);
				} catch (ignored) {
					return;
				}

				if (to.protocol !== 'http:' && to.protocol !== 'https:') { return; }
				if (to.origin === location.origin) { return; }
				if (!document.querySelector('video, audio')) { return; }

				event.preventDefault();
				event.stopPropagation();
				pauseMedia();

				setTimeout(() => { location.href = to.href; }, 400);
			}, true);

			// backstop for everything a click handler cannot see: a script-driven
			// navigation, a form post, a redirect
			window.addEventListener('beforeunload', pauseMedia, true);
			window.addEventListener('pagehide', pauseMedia, true);

			// <base target="_blank"> would otherwise retarget every link on the page
			const base = document.querySelector('base[target]');

			if (base) { base.removeAttribute('target'); }

			let fullscreen = null;

			const fill = (el) => {
				el.__lunaTvStyle = el.getAttribute('style') || '';

				const set = (name, value) => el.style.setProperty(name, value, 'important');

				set('position', 'fixed');
				set('left', '0');
				set('top', '0');
				set('width', '100vw');
				set('height', '100vh');
				set('max-width', '100vw');
				set('max-height', '100vh');
				set('margin', '0');
				set('z-index', '2147483647');
				set('background', '#000');
				document.documentElement.style.setProperty('overflow', 'hidden', 'important');
			};

			const unfill = (el) => {
				if (el.__lunaTvStyle) {
					el.setAttribute('style', el.__lunaTvStyle);
				} else {
					el.removeAttribute('style');
				}

				delete el.__lunaTvStyle;
				document.documentElement.style.removeProperty('overflow');
			};

			const announce = () => {
				for (const name of ['fullscreenchange', 'webkitfullscreenchange']) {
					document.dispatchEvent(new Event(name));
				}
			};

			const enter = function () {
				if (fullscreen === this) { return Promise.resolve(); }

				if (fullscreen) { unfill(fullscreen); }

				fullscreen = this;
				fill(this);
				announce();

				return Promise.resolve();
			};

			const leave = () => {
				if (fullscreen) {
					unfill(fullscreen);
					fullscreen = null;
					announce();
				}

				return Promise.resolve();
			};

			Element.prototype.requestFullscreen = enter;
			Element.prototype.webkitRequestFullscreen = enter;
			Element.prototype.webkitRequestFullScreen = enter;
			Element.prototype.mozRequestFullScreen = enter;
			document.exitFullscreen = leave;
			document.webkitExitFullscreen = leave;
			document.mozCancelFullScreen = leave;

			for (const name of ['fullscreenElement', 'webkitFullscreenElement', 'mozFullScreenElement']) {
				Object.defineProperty(document, name, { configurable: true, get: () => fullscreen });
			}

			for (const name of ['fullscreenEnabled', 'webkitFullscreenEnabled']) {
				Object.defineProperty(document, name, { configurable: true, get: () => true });
			}

		})();
		""";

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

	/** Our page's target id, so a tab that is not ours can be recognised. */
	private final String targetId;

	private ImageReader jpegReader;
	private BufferedImage decodeTarget;
	private int[] columnIndex;
	private int columnIndexFor;
	private volatile int[] tone;

	/** One of the DITHER_* modes; {@link #DITHER_OFF} when not dithering. */
	private volatile int ditherMode;

	/**
	 * Per-row dither offsets, three lanes (R, G, B) per column, each entry
	 * already biased by {@link #DITHER_PAD}. Decode thread only.
	 */
	private int[] ditherScratch;
	private volatile long surfaceFixedAt;

	/**
	 * Guards the screencast on/off sequence.
	 *
	 * Three different threads restart it - a rescale from the server thread, a
	 * revive from the CDP event thread, a surface fix from the decode thread -
	 * and each does a stop followed by a start. Interleaved, two of those can
	 * land as start-then-stop, which switches the stream off for good and looks
	 * exactly like a frozen wall that a power cycle cannot clear.
	 */
	private final Object screencast = new Object();
	private final FrameBuffers buffers;
	private final AtomicReference<BrowserFrame> latest = new AtomicReference<>();
	private final AtomicLong decoded = new AtomicLong();

	/**
	 * How often the renderer is asked whether it is still answering.
	 *
	 * A wedged renderer is not a crash: the process lives, the browser process
	 * keeps answering CDP, and only the page stops. Nothing in the socket or the
	 * process tells us, so the only way to notice is to ask the renderer itself.
	 */
	private static final long PING_INTERVAL_MS = 15_000L;

	/** How long one ping may take before it counts as missed. */
	private static final long PING_TIMEOUT_MS = 8_000L;

	/** Missed pings before the browser is declared dead and relaunched. */
	private static final int MAX_MISSED_PINGS = 3;

	private final java.util.concurrent.ScheduledExecutorService watchdog;
	private final java.util.concurrent.atomic.AtomicInteger missedPings =
		new java.util.concurrent.atomic.AtomicInteger();

	private volatile Consumer<Throwable> onDeath;
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
		int brightness,
		String ditherPattern,
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
		this.tone = toneTable(brightness);
		this.ditherMode = ditherModeOf(ditherPattern);

		String socket = process.websocketUrl();
		int slash = socket.lastIndexOf('/');

		this.targetId = slash < 0 ? "" : socket.substring(slash + 1);
		this.buffers = new FrameBuffers(width * height);
		this.decoder = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "LunaTv-Decode-" + process.port());

			thread.setDaemon(true);

			return thread;
		});
		this.currentUrl = url;
		this.minFrameIntervalNanos = 1_000_000_000L / Math.max(1, config.fps());
		this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "LunaTv-Watchdog-" + process.port());

			thread.setDaemon(true);

			return thread;
		});
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
		int brightness,
		String ditherPattern,
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
			scale,
			brightness, ditherPattern, url);

		browser.onDeath = onDeath;

		cdp.onFailure(throwable -> {
			if (browser.closed) {
				return;
			}

			onDeath.accept(throwable);
		});

		browser.begin();
		browser.armWatchdog();

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
		applyMetrics();

		// a hidden tab stops producing compositor frames, which is what froze the
		// wall when a link stole focus; this keeps ours rendering regardless
		cdp.call("Page.setWebLifecycleState", Map.of("state", "active"));
		cdp.call("Emulation.setScrollbarsHidden", Map.of("hidden", true));

		watchNewWindows();
		startScreencast();

		// only now is the real page requested, with the corrected user agent
		cdp.call("Page.navigate", Map.of("url", currentUrl));
	}

	/**
	 * Sets the page's viewport, and declares the screen to be the same size.
	 *
	 * screenWidth/screenHeight matter for fullscreen: the Fullscreen API sizes
	 * to the *screen*, not the viewport, so without these a video going
	 * fullscreen laid itself out against the real window size and did not fill
	 * the wall.
	 */
	private void applyMetrics() {
		cdp.call("Emulation.setDeviceMetricsOverride", Map.of(
			"width", captureWidth,
			"height", captureHeight,
			"deviceScaleFactor", 1,
			"mobile", false,
			"screenWidth", captureWidth,
			"screenHeight", captureHeight));
	}

	/**
	 * Keeps every link in this one tab.
	 *
	 * A target="_blank" link or window.open creates a second tab, which becomes
	 * the rendered one; ours then stops producing frames and every click still
	 * goes to the hidden page, so the wall froze and stopped responding until
	 * the screen was power-cycled.
	 *
	 * Two layers: the page is taught to navigate in place, and anything that
	 * slips past is caught here, closed, and followed in our own tab.
	 */
	private void watchNewWindows() {
		cdp.on("Page.windowOpen", params -> {
			String url = params.has("url") ? params.get("url").getAsString() : "";

			logger.info("Đã bỏ qua yêu cầu mở cửa sổ mới: "
				+ (url.isBlank() ? "(không có địa chỉ)" : url));
		});

		cdp.on("Target.targetCreated", params -> strayTarget(params, "created"));
		cdp.on("Target.targetInfoChanged", params -> strayTarget(params, "changed"));
		cdp.call("Target.setDiscoverTargets", Map.of("discover", true));
		cdp.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", SAME_TAB_SCRIPT));
		cdp.call("Runtime.evaluate", Map.of("expression", SAME_TAB_SCRIPT, "awaitPromise", false));
	}

	private void strayTarget(JsonObject params, String reason) {
		if (closed || !params.has("targetInfo")) {
			return;
		}

		JsonObject info = params.getAsJsonObject("targetInfo");

		if (!info.has("type") || !"page".equals(info.get("type").getAsString())) {
			return;
		}

		String id = info.has("targetId") ? info.get("targetId").getAsString() : "";

		if (id.isEmpty() || id.equals(targetId)) {
			return;
		}

		String url = info.has("url") ? info.get("url").getAsString() : "";

		// closed, not followed: the wall stays where the operator put it
		logger.info("Đóng tab lạ (" + reason + "): " + (url.isBlank() ? "about:blank" : url));
		cdp.call("Target.closeTarget", Map.of("targetId", id));
		revive();
	}

	/**
	 * Brings our own tab back to the front and restarts the frame stream.
	 *
	 * Closing the stray tab is not enough on its own: the compositor has already
	 * stopped for a backgrounded page, and the screencast does not resume by
	 * itself.
	 */
	private void revive() {
		cdp.call("Target.activateTarget", Map.of("targetId", targetId));
		cdp.call("Page.setWebLifecycleState", Map.of("state", "active"));

		synchronized (screencast) {
			cdp.call("Page.stopScreencast", Map.of());
			startScreencast();
		}
	}

	/**
	 * Puts the capture surface back to the size we asked for.
	 *
	 * Something inside the page can resize the compositor surface out from under
	 * us - a player in an iframe taking real fullscreen is the case seen in the
	 * wild - and Chromium then keeps delivering frames at that size long after
	 * the page has left fullscreen (measured: 1536x768 requested, 800x544
	 * delivered, sticky). Re-applying the metrics override restores it.
	 *
	 * Throttled, because the frames that trigger it arrive many times a second
	 * and the fix takes a moment to land.
	 */
	private void restoreSurface() {
		long now = System.currentTimeMillis();

		if (now - surfaceFixedAt < SURFACE_FIX_INTERVAL_MS) {
			return;
		}

		surfaceFixedAt = now;
		logger.info("Kích thước khung hình bị lệch, đang đặt lại bề mặt vẽ về "
			+ captureWidth + "x" + captureHeight + ".");
		applyMetrics();

		synchronized (screencast) {
			cdp.call("Page.stopScreencast", Map.of());
			startScreencast();
		}
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
	/**
	 * Sets the picture brightness, as a percentage with 100 meaning untouched.
	 *
	 * Applied as a gamma curve rather than a straight multiply, so midtones
	 * lift while highlights stay put instead of clipping to white. It is a
	 * 256-entry lookup, so the cost per pixel is an array read.
	 *
	 * @param brightness percentage, 50..200
	 */
	public void brightness(int brightness) {
		tone = toneTable(brightness);
	}

	/**
	 * Switches the ordered dither pattern, live.
	 *
	 * The next published frame picks the change up; nothing restarts.
	 *
	 * @param pattern bayer, a1..a4, or an empty string to stop dithering
	 */
	public void dither(String pattern) {
		ditherMode = ditherModeOf(pattern);
	}

	private static int ditherModeOf(String pattern) {
		if (pattern == null) {
			return DITHER_OFF;
		}

		return switch (pattern) {
			case "bayer" -> DITHER_BAYER;
			case "a1" -> DITHER_A_XOR;
			case "a2" -> DITHER_A_XOR_CHANNEL;
			case "a3" -> DITHER_A_ADD;
			case "a4" -> DITHER_A_ADD_CHANNEL;
			default -> DITHER_OFF;
		};
	}

	/**
	 * Fills one destination row's dither offsets, three lanes per column.
	 *
	 * The a-dither masks are computed with their published magic constants;
	 * the per-channel variants add c*17 (xor) or c*67 (add) to x per lane.
	 * Everything lands pre-biased, so the pixel loop is add-and-look-up only.
	 *
	 * @param offsets the scratch row, width*3 entries
	 * @param mode which DITHER_* pattern to fill with
	 * @param y the destination row
	 */
	private void fillDitherOffsets(int[] offsets, int mode, int y) {
		switch (mode) {
			case DITHER_BAYER -> {
				int[] row = BAYER_OFFSETS[y & 7];

				for (int x = 0; x < width; x++) {
					int offset = row[x & 7];
					int lane = x * 3;

					offsets[lane] = offset;
					offsets[lane + 1] = offset;
					offsets[lane + 2] = offset;
				}
			}

			case DITHER_A_XOR -> {
				int yk = y * 149;

				for (int x = 0; x < width; x++) {
					int offset = DITHER_OFF_511[((x ^ yk) * 1234) & 511];
					int lane = x * 3;

					offsets[lane] = offset;
					offsets[lane + 1] = offset;
					offsets[lane + 2] = offset;
				}
			}

			case DITHER_A_XOR_CHANNEL -> {
				int yk = y * 149;

				for (int x = 0; x < width; x++) {
					int lane = x * 3;

					offsets[lane] = DITHER_OFF_511[((x ^ yk) * 1234) & 511];
					offsets[lane + 1] = DITHER_OFF_511[(((x + 17) ^ yk) * 1234) & 511];
					offsets[lane + 2] = DITHER_OFF_511[(((x + 34) ^ yk) * 1234) & 511];
				}
			}

			case DITHER_A_ADD -> {
				int yk = y * 237;

				for (int x = 0; x < width; x++) {
					int offset = DITHER_OFF_255[((x + yk) * 119) & 255];
					int lane = x * 3;

					offsets[lane] = offset;
					offsets[lane + 1] = offset;
					offsets[lane + 2] = offset;
				}
			}

			default -> {
				int yk = y * 236;

				for (int x = 0; x < width; x++) {
					int lane = x * 3;

					offsets[lane] = DITHER_OFF_255[((x + yk) * 119) & 255];
					offsets[lane + 1] = DITHER_OFF_255[((x + 67 + yk) * 119) & 255];
					offsets[lane + 2] = DITHER_OFF_255[((x + 134 + yk) * 119) & 255];
				}
			}
		}
	}

	private static int[] toneTable(int brightness) {
		int safe = Math.max(50, Math.min(200, brightness == 0 ? 100 : brightness));

		if (safe == 100) {
			return null;
		}

		int[] table = new int[256];
		double exponent = 100.0 / safe;

		for (int value = 0; value < 256; value++) {
			table[value] = (int) Math.round(255.0 * Math.pow(value / 255.0, exponent));
		}

		return table;
	}

	public void rescale(int scale) {
		int safe = Math.max(1, Math.min(4, scale));

		captureWidth = Math.max(1, width / safe);
		captureHeight = Math.max(1, height / safe);
		columnIndex = null;

		applyMetrics();

		synchronized (screencast) {
			cdp.call("Page.stopScreencast", Map.of());
			startScreencast();
		}
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

		// Whatever size arrived is stretched over the whole wall. Scaling from the
		// real frame size (not the requested one) is what makes a transient
		// mismatch a slightly softer picture for one frame instead of a dropped
		// frame, or worse, a stale strip down the side.
		int[] columns = columns(sourceWidth);
		int[] curve = tone;
		int mode = ditherMode;
		// the dither path folds the tone curve in unconditionally, so a screen at
		// 100% brightness runs it through an identity table instead of a branch
		int[] shade = curve == null ? IDENTITY_TONE : curve;
		int[] offsets = null;

		if (mode != DITHER_OFF) {
			if (ditherScratch == null || ditherScratch.length != width * 3) {
				ditherScratch = new int[width * 3];
			}

			offsets = ditherScratch;
		}

		int lastSourceRow = -1;
		int lastDestinationRow = 0;

		for (int y = 0; y < height; y++) {
			int sourceRow = Math.min(sourceHeight - 1, y * sourceHeight / height);
			int destinationRow = y * width;

			// a source row repeated across several destination rows is copied
			// wholesale rather than rebuilt pixel by pixel; with dithering on this
			// repeats the pattern row too, i.e. the dither works at capture
			// resolution vertically, which is where the detail comes from anyway
			if (sourceRow == lastSourceRow) {
				System.arraycopy(pixels, lastDestinationRow, pixels, destinationRow, width);

				continue;
			}

			int base = sourceRow * sourceWidth;

			if (offsets != null) {
				fillDitherOffsets(offsets, mode, y);

				for (int x = 0; x < width; x++) {
					int at = (base + columns[x]) * 3;
					int lane = x * 3;

					pixels[destinationRow + x] = 0xFF000000
						| (DITHER_CLAMP[shade[source[at + 2] & 0xFF] + offsets[lane]] << 16)
						| (DITHER_CLAMP[shade[source[at + 1] & 0xFF] + offsets[lane + 1]] << 8)
						| DITHER_CLAMP[shade[source[at] & 0xFF] + offsets[lane + 2]];
				}
			} else if (curve == null) {
				for (int x = 0; x < width; x++) {
					int at = (base + columns[x]) * 3;

					// the palette treats a transparent pixel as a hole, so alpha is
					// forced opaque here rather than trusted from the decoder
					pixels[destinationRow + x] = 0xFF000000
						| ((source[at + 2] & 0xFF) << 16)
						| ((source[at + 1] & 0xFF) << 8)
						| (source[at] & 0xFF);
				}
			} else {
				for (int x = 0; x < width; x++) {
					int at = (base + columns[x]) * 3;

					pixels[destinationRow + x] = 0xFF000000
						| (curve[source[at + 2] & 0xFF] << 16)
						| (curve[source[at + 1] & 0xFF] << 8)
						| curve[source[at] & 0xFF];
				}
			}

			lastSourceRow = sourceRow;
			lastDestinationRow = destinationRow;
		}

		if (sourceWidth != captureWidth || sourceHeight != captureHeight) {
			TvDebug.sampled("resize:" + debugPort(), 20,
				"khung hình " + sourceWidth + "x" + sourceHeight + " khác kích thước yêu cầu "
				+ captureWidth + "x" + captureHeight + ", đã giãn cho khớp");
			restoreSurface();
		}

		int frameWidth = width;
		int frameHeight = height;

		decoded.incrementAndGet();
		TvDebug.sampled("decode:" + debugPort(), 100,
			"decode port=" + debugPort() + " " + frameWidth + "x" + frameHeight
			+ " (viewport " + width + "x" + height + ")");

		BrowserFrame previous = latest.getAndSet(new BrowserFrame(pixels, frameWidth, frameHeight, width));

		TvDebug.sampled("handoff:" + debugPort(), 100,
			"published " + frameWidth + "x" + frameHeight
			+ " browser=@" + Integer.toHexString(System.identityHashCode(this))
			+ " overwrote=" + (previous != null));

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
	 * Derived from the frame that actually arrived rather than the size we asked
	 * Chromium for. Those differ in practice: during startup and around a
	 * resize it sends a uniformly scaled-down frame, and a build that assumed
	 * the requested size dropped every one of them.
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
			table[x] = Math.min(sourceWidth - 1, x * sourceWidth / width);
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

		// same reason as the injected pause: navigating away from a playing page
		// wedges this headless build's renderer
		cdp.send("Runtime.evaluate", Map.of(
			"expression", "document.querySelectorAll('video, audio')"
				+ ".forEach(m => { try { m.pause(); } catch (e) {} })",
			"returnByValue", true));

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
	 * Wipes everything the browser remembers about the sites it has visited.
	 *
	 * Cookies, cache, local and session storage, IndexedDB, service workers and
	 * the rest, cleared across every origin rather than only the current one:
	 * the point of the verb is that whoever uses the screen next inherits
	 * nothing, and a login left behind on a site nobody is looking at is
	 * exactly what would be missed.
	 *
	 * The page is reloaded afterwards, because a page holding a live session in
	 * memory keeps showing it until it asks the server again.
	 *
	 * @param reload whether to reload the current page once the wipe is done
	 * @return completes when the browser has acknowledged the clear
	 */
	public java.util.concurrent.CompletableFuture<Void> clearData(boolean reload) {
		// Storage.clearDataForOrigin with an empty origin is not a wildcard, so
		// the sweep is the browser-wide calls plus a per-origin pass over what
		// the storage tracker knows about
		java.util.concurrent.CompletableFuture<?> cleared = java.util.concurrent.CompletableFuture.allOf(
			cdp.call("Network.clearBrowserCookies", Map.of()),
			cdp.call("Network.clearBrowserCache", Map.of()),
			cdp.call("Storage.clearCookies", Map.of()));

		return cleared
			.thenCompose(ignored -> clearOrigins())
			.thenRun(() -> {
				if (reload) {
					// ignoreCache: the cache was just dropped, and a conditional
					// request would put a fresh copy of it straight back
					cdp.call("Page.reload", Map.of("ignoreCache", true));
				}
			});
	}

	/**
	 * Clears every storage bucket for the origins the browser has records for.
	 *
	 * A failure here is not fatal: the cookie and cache wipe above is the part
	 * that matters most, and an origin that refuses is usually one already gone.
	 */
	private java.util.concurrent.CompletableFuture<Void> clearOrigins() {
		String types = "appcache,cookies,file_systems,indexeddb,local_storage,shader_cache,"
			+ "websql,service_workers,cache_storage,interest_groups,shared_storage,"
			+ "storage_buckets,all";

		String origin = originOf(currentUrl);

		if (origin == null) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}

		return cdp.call("Storage.clearDataForOrigin",
				Map.of("origin", origin, "storageTypes", types))
			.handle((result, throwable) -> null)
			.thenApply(ignored -> null);
	}

	private static String originOf(String url) {
		try {
			java.net.URI parsed = java.net.URI.create(url);

			if (parsed.getScheme() == null || parsed.getHost() == null) {
				return null;
			}

			return parsed.getScheme() + "://" + parsed.getHost()
				+ (parsed.getPort() < 0 ? "" : ":" + parsed.getPort());
		} catch (RuntimeException exception) {
			return null;
		}
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

	/**
	 * Starts asking the renderer, periodically, whether it is still alive.
	 *
	 * The failure this exists for looks like nothing else: the Chromium process
	 * is healthy, the CDP socket is open, the browser process answers every
	 * command it handles itself, and only the renderer stops. Anything needing
	 * the page - evaluate, navigate, even a reload - then waits forever, so the
	 * screen freezes with no error anywhere and no way back except a relaunch.
	 * Without this the operator has to notice and power-cycle the screen by hand.
	 */
	private void armWatchdog() {
		watchdog.scheduleWithFixedDelay(this::pingRenderer,
			PING_INTERVAL_MS, PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private void pingRenderer() {
		if (closed) {
			return;
		}

		try {
			cdp.call("Runtime.evaluate", Map.of("expression", "1", "returnByValue", true))
				.get(PING_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

			missedPings.set(0);
		} catch (Throwable throwable) {
			if (closed) {
				return;
			}

			int missed = missedPings.incrementAndGet();

			TvDebug.log("watchdog port=" + debugPort() + " ping bỏ lỡ " + missed
				+ "/" + MAX_MISSED_PINGS + ": " + throwable);

			// a page Chromium has frozen answers again once it is told to be
			// active, so the cheap nudge is tried before the expensive relaunch
			if (missed == 1) {
				cdp.call("Page.setWebLifecycleState", Map.of("state", "active"));
			}

			if (missed >= MAX_MISSED_PINGS) {
				logger.warn("Trình duyệt không phản hồi (" + missed + " lần liên tiếp), đang mở lại.");
				missedPings.set(0);

				Consumer<Throwable> death = onDeath;

				if (death != null) {
					death.accept(new IllegalStateException("renderer không phản hồi"));
				}
			}
		}
	}

	@Override
	public void close() {
		closed = true;
		watchdog.shutdownNow();

		// The JDK's JPEG reader is locked to whichever thread used it, so
		// disposing it from the caller's thread throws. It is handed back to the
		// decode thread instead, and the executor is shut down gracefully so
		// that task still runs; the thread is a daemon, so nothing is waited on.
		try {
			decoder.execute(() -> {
				if (jpegReader != null) {
					jpegReader.dispose();
					jpegReader = null;
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// already shut down
		}

		decoder.shutdown();

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
