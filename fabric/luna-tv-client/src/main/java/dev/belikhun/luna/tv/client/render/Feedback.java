package dev.belikhun.luna.tv.client.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The marks a screen draws on its own surface to answer the player's hand.
 *
 * Input on a wall is otherwise silent: the page reacts a video-latency later,
 * so for a quarter of a second a click that landed and a click that missed
 * look identical. These marks close that gap. The pointer says where a click
 * would land before it is made, the ripple says one was sent, and the drifting
 * bar says the wheel went through to the page rather than the hotbar.
 *
 * Everything here runs on the client's render thread: the GLFW callbacks, the
 * tick and the world render event all share it, so plain fields are enough.
 */
public final class Feedback {

	/** The local player's pointer, as the input layer sees it this frame. */
	public record Pointer(String screen, double u, double v, boolean strong) {
	}

	private enum Kind {
		CLICK_LEFT,
		CLICK_RIGHT,
		SCROLL
	}

	/** One transient mark: a click's ripple or a scroll's drift. */
	private record Pulse(String screen, double u, double v, long at, Kind kind, int direction) {
	}

	// every size is in blocks rather than a fraction of the wall, so a mark
	// reads the same on a bedside screen and a cinema; one map pixel is 1/128
	// of a block for scale
	private static final double CURSOR_BACK_HALF = 0.055;
	private static final double CURSOR_BODY_HALF = 0.03;
	private static final double CURSOR_RING_HALF = 0.09;
	private static final double CURSOR_RING_THICKNESS = 0.02;

	private static final double CLICK_FROM = 0.06;
	private static final double CLICK_TO = 0.3;
	private static final double CLICK_THICKNESS = 0.035;
	private static final long CLICK_MS = 280L;

	private static final double SCROLL_HALF_WIDTH = 0.1;
	private static final double SCROLL_HALF_HEIGHT = 0.018;
	private static final double SCROLL_TRAVEL = 0.28;
	private static final long SCROLL_MS = 250L;

	/** More queued pulses than this is button mashing, not feedback. */
	private static final int MAX_PULSES = 24;

	private static final int BACK_RGB = 0x000000;
	private static final int WHITE_RGB = 0xFFFFFF;

	/** A right click's ripple, tinted so the two buttons read apart. */
	private static final int RIGHT_RGB = 0x7FB8FF;

	private static final List<Pulse> pulses = new ArrayList<>();

	private static Supplier<Pointer> pointer;

	private Feedback() {
	}

	/**
	 * Wires where the pointer is asked from.
	 *
	 * A supplier rather than a value pushed per tick, because ticks run at
	 * twenty a second and frames do not: asking fresh at draw time is what
	 * keeps the pointer gliding across the wall instead of stepping.
	 *
	 * @param source the input layer's current aim, or null when off-screen
	 */
	public static void pointer(Supplier<Pointer> source) {
		pointer = source;
	}

	/**
	 * Records a click, to be drawn as a ripple where it landed.
	 *
	 * @param screen the screen the click went to
	 * @param u across the picture, 0 to 1
	 * @param v down the picture, 0 to 1
	 * @param right whether it was the right button
	 */
	public static void click(String screen, double u, double v, boolean right) {
		pulse(new Pulse(screen, u, v, System.currentTimeMillis(),
			right ? Kind.CLICK_RIGHT : Kind.CLICK_LEFT, 0));
	}

	/**
	 * Records a wheel notch, drawn as a bar drifting the way the page went.
	 *
	 * @param screen the screen the scroll went to
	 * @param u across the picture, 0 to 1
	 * @param v down the picture, 0 to 1
	 * @param direction 1 when the page scrolls down, -1 when it scrolls up
	 */
	public static void scroll(String screen, double u, double v, int direction) {
		pulse(new Pulse(screen, u, v, System.currentTimeMillis(), Kind.SCROLL,
			direction >= 0 ? 1 : -1));
	}

	/** Forgets every pending mark, for a disconnect. */
	public static void reset() {
		pulses.clear();
	}

	/**
	 * Draws this screen's marks.
	 *
	 * Called once per screen per frame, right after its picture, so the marks
	 * sit above the page in draw order as well as in lift.
	 *
	 * @param sink where this frame's geometry goes
	 * @param screen the screen being drawn
	 * @param quad where that screen is, in world coordinates
	 */
	public static void draw(ScreenSink sink, String screen, ScreenQuad quad) {
		double width = quad.width();
		double height = quad.height();

		if (width <= 0.0 || height <= 0.0) {
			return;
		}

		long now = System.currentTimeMillis();

		drawPulses(sink, screen, quad, width, height, now);
		drawPointer(sink, screen, quad, width, height);
	}

	private static void pulse(Pulse fresh) {
		if (pulses.size() >= MAX_PULSES) {
			pulses.remove(0);
		}

		pulses.add(fresh);
	}

	private static void drawPulses(
		ScreenSink sink,
		String screen,
		ScreenQuad quad,
		double width,
		double height,
		long now
	) {
		Iterator<Pulse> iterator = pulses.iterator();

		while (iterator.hasNext()) {
			Pulse pulse = iterator.next();
			long life = pulse.kind == Kind.SCROLL ? SCROLL_MS : CLICK_MS;
			long age = now - pulse.at;

			if (age >= life) {
				iterator.remove();

				continue;
			}

			if (!pulse.screen.equals(screen)) {
				continue;
			}

			double progress = age / (double) life;

			if (pulse.kind == Kind.SCROLL) {
				drawScroll(sink, quad, width, height, pulse, progress);
			} else {
				drawClick(sink, quad, width, height, pulse, progress);
			}
		}
	}

	private static void drawClick(
		ScreenSink sink,
		ScreenQuad quad,
		double width,
		double height,
		Pulse pulse,
		double progress
	) {
		double half = CLICK_FROM + (CLICK_TO - CLICK_FROM) * progress;
		int rgb = pulse.kind == Kind.CLICK_RIGHT
			? RIGHT_RGB
			: WHITE_RGB;

		ring(sink, quad, width, height, pulse.u, pulse.v, half, CLICK_THICKNESS,
			fade(rgb, (1.0 - progress) * 0.8));
	}

	private static void drawScroll(
		ScreenSink sink,
		ScreenQuad quad,
		double width,
		double height,
		Pulse pulse,
		double progress
	) {
		double drift = SCROLL_TRAVEL * progress * pulse.direction / height;

		// the leading bar and a fainter one trailing it: two of them are what
		// read as motion rather than a blink
		rect(sink, quad, width, height, pulse.u, pulse.v + drift,
			SCROLL_HALF_WIDTH, SCROLL_HALF_HEIGHT, fade(WHITE_RGB, (1.0 - progress) * 0.7));
		rect(sink, quad, width, height, pulse.u, pulse.v + drift * 0.55,
			SCROLL_HALF_WIDTH * 0.8, SCROLL_HALF_HEIGHT, fade(WHITE_RGB, (1.0 - progress) * 0.35));
	}

	private static void drawPointer(
		ScreenSink sink,
		String screen,
		ScreenQuad quad,
		double width,
		double height
	) {
		Supplier<Pointer> source = pointer;
		Pointer at = source == null
			? null
			: source.get();

		if (at == null || !at.screen().equals(screen)) {
			return;
		}

		rect(sink, quad, width, height, at.u(), at.v(),
			CURSOR_BACK_HALF, CURSOR_BACK_HALF, fade(BACK_RGB, 0.7));
		rect(sink, quad, width, height, at.u(), at.v(),
			CURSOR_BODY_HALF, CURSOR_BODY_HALF, fade(WHITE_RGB, at.strong() ? 1.0 : 0.85));

		// the ring appears while the player is actually steering, so hovering
		// looks different from merely glancing at the wall
		if (at.strong()) {
			ring(sink, quad, width, height, at.u(), at.v(),
				CURSOR_RING_HALF, CURSOR_RING_THICKNESS, fade(WHITE_RGB, 0.7));
		}
	}

	/** A hollow square: the sides stop short so no corner is blended twice. */
	private static void ring(
		ScreenSink sink,
		ScreenQuad quad,
		double width,
		double height,
		double u,
		double v,
		double half,
		double thickness,
		int argb
	) {
		double halfT = thickness / 2.0;

		rect(sink, quad, width, height, u, v - half + halfT, half, halfT, argb);
		rect(sink, quad, width, height, u, v + half - halfT, half, halfT, argb);
		rect(sink, quad, width, height, u - half + halfT, v, halfT, half - thickness, argb);
		rect(sink, quad, width, height, u + half - halfT, v, halfT, half - thickness, argb);
	}

	/**
	 * One rectangle, centred in fractions and sized in blocks.
	 *
	 * Sizing in blocks and dividing by the wall's own span is what keeps a mark
	 * square: a fraction of the width and the same fraction of the height are
	 * different lengths on any wall that is not.
	 */
	private static void rect(
		ScreenSink sink,
		ScreenQuad quad,
		double width,
		double height,
		double u,
		double v,
		double halfWidth,
		double halfHeight,
		int argb
	) {
		double du = halfWidth / width;
		double dv = halfHeight / height;
		double u0 = Math.max(0.0, u - du);
		double v0 = Math.max(0.0, v - dv);
		double u1 = Math.min(1.0, u + du);
		double v1 = Math.min(1.0, v + dv);

		// clamped away entirely: the mark sits off the picture, and a mark
		// hanging past the wall's edge would float in the air
		if (u0 >= u1 || v0 >= v1) {
			return;
		}

		sink.mark(quad, u0, v0, u1, v1, argb);
	}

	private static int fade(int rgb, double alpha) {
		int level = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);

		return (level << 24) | rgb;
	}
}
