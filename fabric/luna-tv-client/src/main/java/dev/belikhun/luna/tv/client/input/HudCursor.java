package dev.belikhun.luna.tv.client.input;

/**
 * The marks beside the crosshair that say what the screen has taken over.
 *
 * Both swaps this reports are invisible otherwise: the wheel silently stops
 * changing the hotbar, and the keyboard silently stops being movement. A player
 * who does not know it happened will think their controls are broken, so each
 * one gets a mark, and the crosshair itself becomes a caret while the keyboard
 * belongs to a text field.
 *
 * Drawn from rectangles rather than a texture. Glyphs this small are a handful
 * of fills, and it saves shipping an image, a resource pack namespace and a
 * second copy of both for the other game line.
 */
public final class HudCursor {

	private static final int BODY = 0xC8FFFFFF;
	private static final int OUTLINE = 0xB4000000;

	/** How far right of the crosshair's centre the first mark sits. */
	private static final int OFFSET_X = 8;

	/** A gap between two marks when both are showing. */
	private static final int GAP = 3;

	private static final int MOUSE_WIDTH = 7;
	private static final int MOUSE_HEIGHT = 10;
	private static final int KEYS_WIDTH = 11;
	private static final int KEYS_HEIGHT = 8;

	private static volatile boolean scroll;
	private static volatile boolean keyboard;

	private HudCursor() {
	}

	/** Fills one rectangle, whatever the game line calls the thing that does it. */
	public interface Filler {

		void fill(int left, int top, int right, int bottom, int argb);
	}

	public static void scroll(boolean showing) {
		scroll = showing;
	}

	public static void keyboard(boolean showing) {
		keyboard = showing;
	}

	/** Whether the crosshair should be a caret rather than a cross. */
	public static boolean keyboardCaptured() {
		return keyboard;
	}

	/**
	 * Draws whichever marks apply, beside the crosshair.
	 *
	 * @param filler the game's rectangle fill
	 * @param guiWidth the scaled screen width
	 * @param guiHeight the scaled screen height
	 */
	public static void draw(Filler filler, int guiWidth, int guiHeight) {
		int left = guiWidth / 2 + OFFSET_X;
		int middle = guiHeight / 2;

		if (keyboard) {
			keys(filler, left, middle - KEYS_HEIGHT / 2);
			left += KEYS_WIDTH + GAP;
		}

		if (scroll) {
			mouse(filler, left, middle - MOUSE_HEIGHT / 2);
		}
	}

	/**
	 * Replaces the crosshair with a text caret.
	 *
	 * The same thing a browser does over an input, and for the same reason: the
	 * pointer is no longer aiming at anything, it is sitting in a text field.
	 */
	public static void caret(Filler filler, int guiWidth, int guiHeight) {
		int x = guiWidth / 2;
		int y = guiHeight / 2;

		// the stem, outlined so it reads over a light page as well as a dark one
		filler.fill(x - 1, y - 5, x + 2, y + 5, OUTLINE);
		filler.fill(x, y - 4, x + 1, y + 4, BODY);

		// and the serifs top and bottom, which are what make it a caret
		filler.fill(x - 3, y - 6, x + 4, y - 4, OUTLINE);
		filler.fill(x - 2, y - 5, x + 3, y - 4, BODY);
		filler.fill(x - 3, y + 4, x + 4, y + 6, OUTLINE);
		filler.fill(x - 2, y + 4, x + 3, y + 5, BODY);
	}

	private static void mouse(Filler filler, int left, int top) {
		filler.fill(left, top, left + MOUSE_WIDTH, top + MOUSE_HEIGHT, OUTLINE);
		filler.fill(left + 1, top + 1, left + MOUSE_WIDTH - 1, top + MOUSE_HEIGHT - 1, BODY);

		// the wheel: a dark notch near the top, where a real one sits
		filler.fill(left + MOUSE_WIDTH / 2, top + 2, left + MOUSE_WIDTH / 2 + 1, top + 5, OUTLINE);
	}

	private static void keys(Filler filler, int left, int top) {
		filler.fill(left, top, left + KEYS_WIDTH, top + KEYS_HEIGHT, OUTLINE);
		filler.fill(left + 1, top + 1, left + KEYS_WIDTH - 1, top + KEYS_HEIGHT - 1, BODY);

		// three rows of keys, the bottom one a space bar
		for (int column = 2; column <= KEYS_WIDTH - 3; column += 2) {
			filler.fill(left + column, top + 2, left + column + 1, top + 3, OUTLINE);
			filler.fill(left + column, top + 4, left + column + 1, top + 5, OUTLINE);
		}

		filler.fill(left + 3, top + 6, left + KEYS_WIDTH - 3, top + 7, OUTLINE);
	}
}
