package dev.belikhun.luna.tv.display;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import de.pianoman911.mapengine.api.drawing.IDrawingSpace;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.browser.BrowserFrame;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenState;
import dev.belikhun.luna.tv.screen.TvScreen;

/**
 * The thread that puts frames on walls.
 *
 * Its own thread rather than a Bukkit task, for two reasons: the scheduler's
 * grain is one tick (50ms), which would cap the wall at 20fps whatever the
 * config said, and a frame push must never share a thread with game logic.
 * MapEngine supports flushing off the main thread, which is what makes this
 * legal.
 *
 * Pacing is absolute, against a start instant, so a slow frame is caught up on
 * rather than compounding into permanent drift.
 */
public final class RenderPump implements Runnable {

	/** How often a non-running screen redraws its notice. */
	private static final long PLACEHOLDER_INTERVAL_MS = 1_000L;

	/** Bytes one whole map costs on the wire, palette form. */
	private static final int MAP_BYTES = 128 * 128;

	private final LunaLogger logger;
	private final Supplier<Collection<ScreenInstance>> screens;

	private volatile TvConfig config;
	private volatile boolean running;

	private Thread thread;

	public RenderPump(LunaLogger logger, TvConfig config, Supplier<Collection<ScreenInstance>> screens) {
		this.logger = logger;
		this.config = config;
		this.screens = screens;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/** Starts the render thread. */
	public void start() {
		if (thread != null) {
			return;
		}

		running = true;
		thread = new Thread(this, "LunaTv-Render");
		thread.setDaemon(true);
		thread.start();
	}

	/** Stops the render thread and waits briefly for it to finish a frame. */
	public void stop() {
		running = false;

		if (thread == null) {
			return;
		}

		thread.interrupt();

		try {
			thread.join(TimeUnit.SECONDS.toMillis(2));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}

		thread = null;
	}

	@Override
	public void run() {
		long frame = 0;
		long start = System.nanoTime();

		while (running) {
			long interval = 1_000_000_000L / Math.max(1, pumpRate());

			try {
				pass();
			} catch (Throwable throwable) {
				logger.error("Vòng vẽ khung hình gặp lỗi.", throwable);
			}

			frame++;

			long target = start + frame * interval;
			long wait = target - System.nanoTime();

			// a long stall (a page storm, a GC pause) rebases the clock instead of
			// spinning to catch up on frames nobody will ever see
			if (wait < -interval * 4) {
				start = System.nanoTime();
				frame = 0;
				continue;
			}

			if (wait <= 0) {
				continue;
			}

			try {
				Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();

				return;
			}
		}
	}

	/**
	 * How often the pump ticks: the fastest rate any screen asks for, so a
	 * per-screen fps above the global default is not silently capped by the
	 * loop that serves it. Screens themselves are paced by their browsers.
	 */
	private int pumpRate() {
		int rate = config.fps();

		for (ScreenInstance instance : screens.get()) {
			int own = instance.screen().fps();

			if (own > rate) {
				rate = own;
			}
		}

		return rate;
	}

	private void pass() {
		for (ScreenInstance instance : screens.get()) {
			if (!instance.drawable() || instance.viewers().isEmpty()) {
				TvDebug.sampled("idle:" + instance.name(), 200,
					"skip screen=" + instance.name()
					+ (instance.drawable() ? " (không có người xem)" : " (chưa có drawing space)"));
				continue;
			}

			if (instance.state() == ScreenState.RUNNING) {
				pushBrowser(instance);
				continue;
			}

			pushPlaceholder(instance);
		}
	}

	private void pushBrowser(ScreenInstance instance) {
		CdpBrowser browser = instance.browser();

		if (browser == null) {
			return;
		}

		BrowserFrame frame = browser.poll();
		boolean redraw = instance.consumeRedraw();

		if (frame == null) {
			// This path used to be entirely silent, which made a screen that never
			// draws indistinguishable from one with nothing to draw.
			TvDebug.sampled("idle-frame:" + instance.name(), 100,
				"no frame screen=" + instance.name()
				+ " decoded=" + browser.framesDecoded()
				+ " pushed=" + instance.framesPushed()
				+ " retained=" + (instance.retained() != null)
				+ " browser=@" + Integer.toHexString(System.identityHashCode(browser))
				+ " instance=@" + Integer.toHexString(System.identityHashCode(instance)));

			// nothing new: only worth doing anything if a viewer is owed a re-send,
			// which on a static page is the only way they ever see anything
			if (redraw) {
				send(instance, instance.retained());
			}

			return;
		}

		// The budget is what keeps a big screen watchable over a real connection:
		// a playing video dirties most of a 144-map wall at every frame, which is
		// tens of megabits sustained, and a home connection simply drops behind -
		// including behind the first full frame, which is why an over-budget wall
		// shows nothing at all rather than a slow picture. Frames that do not fit
		// are skipped whole; the next one that fits carries the accumulated change.
		long cost = estimateCost(instance, frame);

		TvScreen budgetScreen = instance.screen();
		int megabits = budgetScreen.maxMegabits() > 0 ? budgetScreen.maxMegabits() : config.maxMegabits();

		if (!instance.budgetTake(cost, megabits)) {
			TvDebug.sampled("budget:" + instance.name(), 50,
				"budget-skip screen=" + instance.name() + " cost=" + (cost / 1024) + "KB");

			// A throttled wall looks broken rather than slow, so the reason is
			// stated plainly once. It bites hardest when the whole picture starts
			// changing at once - a video going fullscreen is the usual trigger.
			if (instance.budgetSkipped()) {
				long full = (long) budgetScreen.mapsWide() * budgetScreen.mapsHigh() * 128 * 128;
				long ceiling = megabits * 1_000_000L / 8 / Math.max(1, full);

				logger.warn("Màn hình '" + instance.name() + "' đang bị giới hạn băng thông: "
					+ "một khung hình toàn màn hình tốn " + (full / 1024 / 1024) + "MB, "
					+ "ngân sách " + megabits + " Mbit/s chỉ đủ ~" + ceiling + " fps. "
					+ "Tăng bằng /lunatv bandwidth " + instance.name() + " <Mbit>, "
					+ "hoặc giảm /lunatv scale · fps.");
			}

			browser.recycle(frame);

			return;
		}

		// the frame is retained for later re-sends, so the buffer it displaces is what
		// goes back to the pool
		BrowserFrame displaced = instance.retain(frame);

		send(instance, frame);

		if (displaced != null) {
			browser.recycle(displaced);
		}
	}

	/**
	 * Writes one frame to the wall.
	 *
	 * Each frame goes into the next buffer of the screen's rotation, because
	 * MapEngine converts and sends on its own thread pool: the buffer handed to
	 * flush() is still being read after flush() returns, and reusing it straight
	 * away is what tears the picture into blocks of two different pages.
	 *
	 * The delta itself is MapEngine's own business - it caches what every viewer
	 * already holds per map - so nothing here tries to help with that.
	 *
	 * @param instance the screen
	 * @param frame the frame to send; a null is a no-op
	 */
	/**
	 * What this frame will roughly cost on the wire, in bytes.
	 *
	 * Counted in whole maps against the frame last sent: a map with any changed
	 * pixel is charged in full, which slightly overstates MapEngine's sub-rect
	 * updates and therefore errs on the safe side of the budget. Comparison
	 * early-exits per map, so a video region is charged after one look and only
	 * genuinely static maps pay the full scan.
	 *
	 * @param instance the screen
	 * @param frame the candidate frame
	 * @return estimated bytes, at least one map's worth
	 */
	private long estimateCost(ScreenInstance instance, BrowserFrame frame) {
		BrowserFrame previous = instance.retained();

		int columns = Math.max(1, frame.stride() / 128);
		int rows = Math.max(1, frame.height() / 128);

		if (previous == null || previous.pixels().length != frame.pixels().length) {
			return (long) columns * rows * MAP_BYTES;
		}

		int[] now = frame.pixels();
		int[] before = previous.pixels();
		int stride = frame.stride();
		long dirty = 0;

		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				if (mapChanged(now, before, stride, column * 128, row * 128, frame.height())) {
					dirty++;
				}
			}
		}

		return Math.max(MAP_BYTES, dirty * MAP_BYTES);
	}

	private static boolean mapChanged(int[] now, int[] before, int stride, int startX, int startY, int height) {
		int endY = Math.min(startY + 128, height);

		for (int y = startY; y < endY; y++) {
			int base = y * stride + startX;

			for (int x = 0; x < 128; x++) {
				if (now[base + x] != before[base + x]) {
					return true;
				}
			}
		}

		return false;
	}

	private void send(ScreenInstance instance, BrowserFrame frame) {
		if (frame == null) {
			return;
		}

		IDrawingSpace drawing = instance.nextDrawing();

		if (drawing == null) {
			return;
		}

		drawing.pixels(frame.pixels(), 0, 0, frame.stride(), frame.height());
		drawing.flush();
		instance.framePushed();

		if (TvDebug.enabled()) {
			StringBuilder who = new StringBuilder();

			if (instance.ctx() != null) {
				for (org.bukkit.entity.Player receiver : instance.ctx().receivers()) {
					if (who.length() > 0) {
						who.append(',');
					}

					// the identity hash separates a live Player object from a stale
					// one left over from before a relog: names and UUIDs match, the
					// object (and its network connection) does not
					who.append(receiver.getName()).append('@')
						.append(Integer.toHexString(System.identityHashCode(receiver)))
						.append(receiver.isOnline() ? "" : "!OFFLINE");
				}
			}

			TvDebug.log("push screen=" + instance.name() + " " + frame.width() + "x" + frame.height()
				+ " -> [" + who + "]");
		}
	}

	private void pushPlaceholder(ScreenInstance instance) {
		if (!instance.placeholderDue(PLACEHOLDER_INTERVAL_MS)) {
			return;
		}

		IDrawingSpace drawing = instance.nextDrawing();

		if (drawing == null) {
			return;
		}

		int width = drawing.buffer().width();
		int height = drawing.buffer().height();

		// powered off means a dark screen, exactly like a real television: solid
		// black, no message
		int[] pixels = instance.state() == ScreenState.OFF
			? PlaceholderFrames.black(width, height)
			: noticePixels(instance, width, height);

		drawing.pixels(pixels, 0, 0, width, height);
		drawing.flush();
	}

	private int[] noticePixels(ScreenInstance instance, int width, int height) {
		Notice notice = noticeFor(instance);

		return PlaceholderFrames.notice(width, height, notice.title(), notice.detail(), notice.accent());
	}

	private Notice noticeFor(ScreenInstance instance) {
		return switch (instance.state()) {
			case STARTING -> new Notice("Đang mở trình duyệt…", instance.screen().url(), true);
			case CRASHED -> new Notice("Trình duyệt gặp lỗi",
				instance.failure() == null ? "Dùng /lunatv refresh " + instance.name() : instance.failure(), false);
			case SUSPENDED -> new Notice("Thế giới chưa được tải", instance.screen().world(), false);
			case RUNNING -> new Notice("Đang tải trang…", instance.screen().url(), true);
			// unreachable: an OFF screen takes the solid-black path before this
			case OFF -> new Notice("", "", false);
		};
	}

	private record Notice(String title, String detail, boolean accent) {}
}
