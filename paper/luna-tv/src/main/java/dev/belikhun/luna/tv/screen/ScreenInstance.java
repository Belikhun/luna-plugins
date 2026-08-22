package dev.belikhun.luna.tv.screen;

import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.pipeline.IPipelineContext;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import dev.belikhun.luna.tv.browser.BrowserFrame;
import dev.belikhun.luna.tv.browser.CdpBrowser;

/**
 * A screen's runtime: what is on the wall, what is drawing it, who can see it.
 *
 * Mutated from the main thread and read by the render thread, so the fields the
 * render thread touches are volatile. Nothing here is persisted; the model in
 * {@link #screen()} is.
 */
public final class ScreenInstance {

	/** How long to wait before each relaunch attempt, in milliseconds. */
	private static final long[] RELAUNCH_BACKOFF = { 5_000L, 15_000L, 60_000L };

	private final TvScreen screen;
	private final Set<UUID> viewers = new LinkedHashSet<>();

	private volatile ScreenState state = ScreenState.SUSPENDED;
	private volatile IMapDisplay display;
	private volatile IPipelineContext ctx;
	private volatile IDrawingSpace[] drawings;
	private int drawingCursor;
	private volatile CdpBrowser browser;
	private volatile String failure;
	private volatile int relaunchAttempt;
	private volatile long relaunchAt;
	private volatile long lastPlaceholderAt;
	private volatile long framesPushed;
	private volatile BrowserFrame retained;
	private volatile boolean redrawRequested;
	private volatile boolean mismatchReported;
	private volatile boolean powered;
	private final java.util.concurrent.atomic.AtomicBoolean launching =
		new java.util.concurrent.atomic.AtomicBoolean();
	private final java.util.concurrent.atomic.AtomicLong budgetSkips =
		new java.util.concurrent.atomic.AtomicLong();
	private volatile boolean budgetWarned;
	private double budgetTokens;
	private long budgetRefilledAt;

	public ScreenInstance(TvScreen screen) {
		this.screen = screen;
	}

	public TvScreen screen() {
		return screen;
	}

	public String name() {
		return screen.name();
	}

	public ScreenState state() {
		return state;
	}

	public void state(ScreenState state) {
		this.state = state;
	}

	public IMapDisplay display() {
		return display;
	}

	public void display(IMapDisplay display) {
		this.display = display;
	}

	/** The pipeline context: receivers, converter, buffering. Shared by all spaces. */
	public IPipelineContext ctx() {
		return ctx;
	}

	/**
	 * Installs the context and the rotation of drawing spaces.
	 *
	 * @param ctx the shared context
	 * @param drawings one drawing space per buffer in the rotation
	 */
	public void pipeline(IPipelineContext ctx, IDrawingSpace[] drawings) {
		this.ctx = ctx;
		this.drawings = drawings;
	}

	/** Whether this screen has somewhere to draw. */
	public boolean drawable() {
		IDrawingSpace[] current = drawings;

		return current != null && current.length > 0;
	}

	/**
	 * The next drawing space in the rotation.
	 *
	 * MapEngine converts and sends a flushed frame on its own thread pool, so the
	 * buffer behind a flush is still being read after flush() returns. Writing
	 * the next frame into that same buffer is what tears a picture into blocks of
	 * two different pages. Rotating means a buffer is only reused once several
	 * frames have gone by.
	 *
	 * @return the space to draw this frame into, or null when detached
	 */
	public IDrawingSpace nextDrawing() {
		IDrawingSpace[] current = drawings;

		if (current == null || current.length == 0) {
			return null;
		}

		synchronized (this) {
			IDrawingSpace next = current[drawingCursor % current.length];
			drawingCursor++;

			return next;
		}
	}

	public CdpBrowser browser() {
		return browser;
	}

	public void browser(CdpBrowser browser) {
		this.browser = browser;
	}

	/** The last error worth telling an operator about, or null. */
	public String failure() {
		return failure;
	}

	public void failure(String failure) {
		this.failure = failure;
	}

	/** The players currently sent this screen's packets. */
	public Set<UUID> viewers() {
		return viewers;
	}

	/**
	 * Schedules the next relaunch, lengthening the wait each time.
	 *
	 * @return true when another attempt is allowed, false once exhausted
	 */
	public boolean scheduleRelaunch() {
		if (relaunchAttempt >= RELAUNCH_BACKOFF.length) {
			return false;
		}

		relaunchAt = System.currentTimeMillis() + RELAUNCH_BACKOFF[relaunchAttempt];
		relaunchAttempt++;

		return true;
	}

	/** Whether a scheduled relaunch is now due. */
	public boolean relaunchDue() {
		return relaunchAt > 0 && System.currentTimeMillis() >= relaunchAt;
	}

	/** Clears the relaunch schedule after a successful start. */
	public void relaunchSettled() {
		relaunchAttempt = 0;
		relaunchAt = 0;
	}

	/** Consumed by the render thread to rate-limit placeholder pushes. */
	public boolean placeholderDue(long intervalMillis) {
		long now = System.currentTimeMillis();

		if (now - lastPlaceholderAt < intervalMillis) {
			return false;
		}

		lastPlaceholderAt = now;

		return true;
	}

	/** Frames actually pushed to viewers; the other half of the blank-wall question. */
	public long framesPushed() {
		return framesPushed;
	}

	public void framePushed() {
		framesPushed++;
	}

	/**
	 * The last frame pushed to the wall, kept rather than recycled.
	 *
	 * A static page emits no new frames, so without holding one there is nothing
	 * to send a viewer who arrives later - they would see empty maps forever.
	 *
	 * @return the retained frame, or null before the first push
	 */
	public BrowserFrame retained() {
		return retained;
	}

	/**
	 * Stores the frame just pushed and returns the one it replaces, so the caller
	 * can hand that back to the browser's buffer pool.
	 *
	 * @param frame the frame that was pushed
	 * @return the previously retained frame, or null
	 */
	public BrowserFrame retain(BrowserFrame frame) {
		BrowserFrame previous = retained;

		retained = frame;

		return previous;
	}


	/**
	 * Claims the right to launch a browser for this screen.
	 *
	 * Chromium takes a couple of seconds to open its debug port, and until it
	 * does nothing records that a launch is under way. Power cycling, a scale
	 * change and the crash relaunch could therefore each start their own, and
	 * every loser of that race stayed alive as an orphan holding ~450MB and a
	 * debug port.
	 *
	 * @return true when the caller may launch
	 */
	public boolean claimLaunch() {
		return launching.compareAndSet(false, true);
	}

	/** Releases the launch claim, whatever the outcome. */
	public void releaseLaunch() {
		launching.set(false);
	}

	/** How many frames the bandwidth budget has dropped. */
	public long budgetSkips() {
		return budgetSkips.get();
	}

	/**
	 * Records a frame dropped for want of budget.
	 *
	 * @return true the first time it is worth telling the operator
	 */
	public boolean budgetSkipped() {
		long count = budgetSkips.incrementAndGet();

		if (count < 150 || budgetWarned) {
			return false;
		}

		budgetWarned = true;

		return true;
	}

	/**
	 * Whether the operator wants this screen running.
	 *
	 * Deliberately not persisted: every screen boots powered off, so a server
	 * restart never brings up a wall of browsers nobody asked for.
	 */
	public boolean powered() {
		return powered;
	}

	public void powered(boolean powered) {
		this.powered = powered;
	}

	/**
	 * Takes bytes from the screen's bandwidth budget.
	 *
	 * A token bucket: refilled continuously at the configured rate, holding at
	 * most one second of burst plus one full wall, so the first frame after a
	 * quiet spell (or a fresh viewer) always fits no matter the budget.
	 *
	 * Called only by the render thread, so no locking.
	 *
	 * @param bytes what the frame is estimated to cost
	 * @param megabits the configured budget, megabits per second
	 * @return true when the frame fits and the tokens were taken
	 */
	public boolean budgetTake(long bytes, int megabits) {
		long now = System.nanoTime();
		double ratePerNano = megabits * 1_000_000.0 / 8.0 / 1_000_000_000.0;

		if (budgetRefilledAt == 0) {
			budgetRefilledAt = now;
			budgetTokens = megabits * 1_000_000.0 / 8.0;
		}

		double wallBytes = (double) screen.mapsWide() * screen.mapsHigh() * 128 * 128;
		double capacity = megabits * 1_000_000.0 / 8.0 + wallBytes;

		budgetTokens = Math.min(capacity, budgetTokens + (now - budgetRefilledAt) * ratePerNano);
		budgetRefilledAt = now;

		if (budgetTokens < bytes) {
			return false;
		}

		budgetTokens -= bytes;

		return true;
	}

	/**
	 * Whether a frame-size mismatch should be logged.
	 *
	 * Once per screen: at twenty frames a second an unconditional warning would
	 * bury the log it is meant to help with.
	 *
	 * @return true the first time it is called
	 */
	public boolean reportMismatch() {
		if (mismatchReported) {
			return false;
		}

		mismatchReported = true;

		return true;
	}

	/** Asks the render thread for a full re-send, e.g. a viewer just spawned. */
	public void requestRedraw() {
		redrawRequested = true;
	}

	/**
	 * Takes the pending redraw request, if any.
	 *
	 * @return true when a full re-send is owed
	 */
	public boolean consumeRedraw() {
		if (!redrawRequested) {
			return false;
		}

		redrawRequested = false;

		return true;
	}

	/** Forces the next placeholder push, so a state change shows at once. */
	public void placeholderStale() {
		lastPlaceholderAt = 0;
	}
}
