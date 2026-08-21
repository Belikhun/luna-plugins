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
		int scale
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
		button(g, "back", 8, 42, 56, 26, CARD, "BACK", 10);
		button(g, "forward", 68, 42, 56, 26, CARD, "FWD", 10);
		button(g, "reload", 128, 42, 56, 26, CARD, "RELOAD", 9);
		button(g, "home", 188, 42, 60, 26, CARD, "HOME", 10);

		// playback row
		button(g, "seekback", 8, 72, 56, 26, CARD, "-10s", 11);
		button(g, "play", 68, 72, 116, 26, CARD_HI, "PLAY / PAUSE", 11);
		button(g, "seekfwd", 188, 72, 60, 26, CARD, "+10s", 11);

		// volume: minus, slider, plus, mute - all on one line
		button(g, "voldown", 8, 102, 24, 22, CARD, "-", 14);
		button(g, "volup", 190, 102, 24, 22, CARD, "+", 12);
		button(g, "mute", 218, 102, 30, 22, state.audio() ? CARD_HI : CARD,
			state.audio() ? "ON" : "OFF", 9);

		int trackX = 36;
		int trackWidth = 150;

		g.setColor(fillFor("volume", CARD));
		g.fillRoundRect(trackX, 102, trackWidth, 22, 6, 6);
		g.setColor(state.audio() ? BLUE : MUTED);
		g.fillRoundRect(trackX, 102, trackWidth * state.volume() / 100, 22, 6, 6);
		widgets.add(new Widget("volume", trackX, 102, trackWidth, 22));

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		g.setColor(TEXT);

		String volumeLabel = state.volume() + "%";

		g.drawString(volumeLabel, trackX + (trackWidth - g.getFontMetrics().stringWidth(volumeLabel)) / 2, 118);

		// arrow cluster, laid out as a d-pad so it reads at a glance
		button(g, "up", 38, 128, 28, 18, CARD, "\u25B2", 11);
		button(g, "left", 8, 148, 28, 18, CARD, "\u25C0", 11);
		button(g, "right", 68, 148, 28, 18, CARD, "\u25B6", 11);
		button(g, "down", 38, 168, 28, 18, CARD, "\u25BC", 11);

		// scroll wheel, and the two keys that pair with browsing
		button(g, "scrollup", 104, 128, 66, 26, CARD, "SCROLL \u25B2", 9);
		button(g, "scrolldown", 104, 160, 66, 26, CARD, "SCROLL \u25BC", 9);
		button(g, "esc", 176, 128, 72, 26, CARD, "ESC", 11);
		button(g, "tab", 176, 160, 72, 26, CARD, "TAB", 11);

		// text keys
		button(g, "enter", 8, 190, 120, 22, CARD_HI, "ENTER", 11);
		button(g, "backspace", 134, 190, 114, 22, CARD, "BACKSPACE", 10);

		// status footer, with room to actually read it
		g.setColor(CARD);
		g.fillRect(0, 218, 256, 38);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
		g.setColor(TEXT);
		g.drawString(trim(state.url(), 42), 8, 234);
		g.setColor(MUTED);
		g.drawString(state.fps() + " fps · 1/" + state.scale() + " · "
			+ (state.audio() ? "có tiếng" : "không tiếng"), 8, 249);

		g.dispose();

		int[] pixels = new int[256 * 256];

		image.getRGB(0, 0, 256, 256, pixels, 0, 256);

		return pixels;
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
