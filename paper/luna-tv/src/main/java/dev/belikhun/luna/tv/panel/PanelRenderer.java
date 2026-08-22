package dev.belikhun.luna.tv.panel;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the 256x256 touch panel and remembers where its controls are.
 *
 * Pure pixels in, hit rectangles out: the service decides what a press means,
 * this class only decides what the panel looks like. Redrawn whole on every
 * change; at this size that costs nothing.
 */
public final class PanelRenderer {

	/** One pressable region of the panel. */
	public record Widget(String id, int x, int y, int width, int height) {

		public boolean hit(int px, int py) {
			return px >= x && px < x + width && py >= y && py < y + height;
		}
	}

	/** Everything the panel shows, captured at render time. */
	public record State(
		String screenName,
		boolean powered,
		String stateLabel,
		boolean running,
		int viewers,
		int volume,
		boolean audio,
		String url,
		int fps,
		int scale,
		int brightness
	) {}

	private static final Color BACKGROUND = new Color(0x10, 0x14, 0x18);
	private static final Color CARD = new Color(0x1C, 0x22, 0x2A);
	private static final Color CARD_HI = new Color(0x2A, 0x33, 0x3E);
	private static final Color TEXT = new Color(0xE8, 0xEC, 0xF1);
	private static final Color MUTED = new Color(0x8A, 0x94, 0xA0);
	private static final Color GREEN = new Color(0x4C, 0xC3, 0x66);
	private static final Color RED = new Color(0xE5, 0x54, 0x54);
	private static final Color BLUE = new Color(0x4D, 0x9F, 0xE8);
	private static final Color GOLD = new Color(0xFF, 0xC8, 0x4D);

	/** Colour a pressed button flashes toward. */
	private static final Color PRESS_TINT = new Color(0x6F, 0xB6, 0xFF);

	private final List<Widget> widgets = new ArrayList<>();

	private String pressedId;
	private float press;

	/** The regions drawn by the last render, for hit testing. */
	public List<Widget> widgets() {
		return widgets;
	}

	/**
	 * Renders the whole panel.
	 *
	 * @param state what to show
	 * @return 256x256 ARGB pixels
	 */
	public int[] render(State state) {
		return render(state, null, 0f);
	}

	/**
	 * Renders the panel with one button lit up mid-press.
	 *
	 * @param state what to show
	 * @param pressedId the widget being pressed, or null
	 * @param press how fresh the press is, 1 at the moment of contact, 0 once faded
	 * @return 256x256 ARGB pixels
	 */
	public int[] render(State state, String pressedId, float press) {
		this.pressedId = pressedId;
		this.press = Math.max(0f, Math.min(1f, press));
		widgets.clear();

		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();

		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, 256, 256);

		// header: name, status, power
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		g.setColor(TEXT);
		g.drawString(state.screenName(), 8, 20);

		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		g.setColor(state.running() ? GREEN : (state.powered() ? GOLD : MUTED));
		g.fillOval(8, 27, 7, 7);
		g.setColor(MUTED);
		g.drawString(state.stateLabel() + " · " + state.viewers() + " người xem", 20, 35);

		button(g, "power", 198, 6, 50, 30,
			state.powered() ? RED : GREEN, state.powered() ? "TẮT" : "BẬT", 12);

		// navigation row
		button(g, "back", 8, 42, 56, 24, CARD, "BACK", 10);
		button(g, "forward", 68, 42, 56, 24, CARD, "FWD", 10);
		button(g, "reload", 128, 42, 56, 24, CARD, "RELOAD", 9);
		button(g, "home", 188, 42, 60, 24, CARD, "HOME", 10);

		// playback row
		button(g, "seekback", 8, 70, 56, 24, CARD, "-10s", 11);
		button(g, "play", 68, 70, 116, 24, CARD_HI, "PLAY / PAUSE", 11);
		button(g, "seekfwd", 188, 70, 60, 24, CARD, "+10s", 11);

		// volume, then brightness: same shape so the pair reads as one group
		slider(g, "volume", 98, "voldown", "volup", state.volume(), 0, 100,
			state.audio() ? BLUE : MUTED, state.volume() + "%",
			"mute", state.audio() ? "ON" : "OFF", state.audio());
		slider(g, "brightness", 124, "brightdown", "brightup", state.brightness(), 50, 200,
			GOLD, "\u2600 " + state.brightness() + "%",
			"brightreset", "100", false);

		// arrows in the keyboard's own shape: up alone, then left/down/right
		button(g, "up", 38, 152, 28, 18, CARD, "\u25B2", 11);
		button(g, "left", 8, 174, 28, 18, CARD, "\u25C0", 11);
		button(g, "down", 38, 174, 28, 18, CARD, "\u25BC", 11);
		button(g, "right", 68, 174, 28, 18, CARD, "\u25B6", 11);

		// scroll wheel, and the two keys that pair with browsing
		button(g, "scrollup", 104, 152, 66, 18, CARD, "SCROLL \u25B2", 9);
		button(g, "scrolldown", 104, 174, 66, 18, CARD, "SCROLL \u25BC", 9);
		button(g, "esc", 176, 152, 72, 18, CARD, "ESC", 10);
		button(g, "tab", 176, 174, 72, 18, CARD, "TAB", 10);

		// text keys
		button(g, "enter", 8, 196, 120, 20, CARD_HI, "ENTER", 11);
		button(g, "backspace", 134, 196, 114, 20, CARD, "BACKSPACE", 10);

		// status footer, with room to actually read it
		g.setColor(CARD);
		g.fillRect(0, 220, 256, 36);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		g.setColor(TEXT);
		g.drawString(trim(state.url(), 42), 8, 236);
		g.setColor(MUTED);
		g.drawString(state.fps() + " fps · 1/" + state.scale() + " · "
			+ (state.audio() ? "có tiếng" : "không tiếng"), 8, 250);

		g.dispose();

		int[] pixels = new int[256 * 256];

		image.getRGB(0, 0, 256, 256, pixels, 0, 256);

		return pixels;
	}

	/**
	 * One value row: a minus, a filled track carrying its own label, a plus, and
	 * a trailing button.
	 *
	 * Volume and brightness are drawn by the same code so they read as a pair;
	 * pressing the track sets the value directly from where it was touched.
	 */
	private void slider(Graphics2D g, String id, int y, String downId, String upId,
			int value, int min, int max, Color fill, String label,
			String trailingId, String trailingLabel, boolean trailingActive) {
		int height = 22;

		button(g, downId, 8, y, 24, height, CARD, "-", 14);
		button(g, upId, 190, y, 24, height, CARD, "+", 12);
		button(g, trailingId, 218, y, 30, height, trailingActive ? CARD_HI : CARD, trailingLabel, 9);

		int trackX = 36;
		int trackWidth = 150;
		int filled = trackWidth * Math.max(0, value - min) / Math.max(1, max - min);

		g.setColor(fillFor(id, CARD));
		g.fillRoundRect(trackX, y, trackWidth, height, 6, 6);
		g.setColor(fill);
		g.fillRoundRect(trackX, y, filled, height, 6, 6);
		widgets.add(new Widget(id, trackX, y, trackWidth, height));

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		g.setColor(TEXT);
		g.drawString(label, trackX + (trackWidth - g.getFontMetrics().stringWidth(label)) / 2, y + 16);
	}

	private void button(Graphics2D g, String id, int x, int y, int width, int height,
			Color fill, String label, int fontSize) {
		g.setColor(fillFor(id, fill));
		g.fillRoundRect(x, y, width, height, 6, 6);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
		g.setColor(TEXT);

		int textWidth = g.getFontMetrics().stringWidth(label);

		g.drawString(label, x + (width - textWidth) / 2, y + (height + fontSize) / 2 - 2);
		widgets.add(new Widget(id, x, y, width, height));
	}

	/**
	 * A button's fill, lifted toward the press tint while it is being pressed.
	 *
	 * The flash is the only feedback a map wall can give for a click, so it
	 * matters more here than the equivalent on a web page: without it there is
	 * no cursor, no hover and no travel to tell you the press registered.
	 *
	 * @param id the widget being drawn
	 * @param base its normal colour
	 * @return the colour to paint
	 */
	private Color fillFor(String id, Color base) {
		if (press <= 0f || pressedId == null || !pressedId.equals(id)) {
			return base;
		}

		return blend(base, PRESS_TINT, press);
	}

	private static Color blend(Color from, Color to, float amount) {
		return new Color(
			Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
			Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
			Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}

	private static String trim(String text, int max) {
		if (text == null) {
			return "";
		}

		return text.length() > max ? text.substring(0, max - 1) + "…" : text;
	}
}
