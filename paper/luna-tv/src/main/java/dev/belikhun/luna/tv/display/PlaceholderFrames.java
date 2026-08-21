package dev.belikhun.luna.tv.display;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The pictures a screen shows when it has no browser frame to show.
 *
 * Drawn with java.awt into an int array, which needs no display: ImageIO and
 * Graphics2D on a BufferedImage work in a headless JVM. A screen that is
 * starting, broken or waiting must still say so on the wall, because the wall
 * is the only place most people will look.
 */
public final class PlaceholderFrames {

	private static final Color BACKGROUND = new Color(0x14, 0x16, 0x1a);
	private static final Color ACCENT = new Color(0x3b, 0x82, 0xf6);
	private static final Color TEXT = new Color(0xe5, 0xe7, 0xeb);
	private static final Color MUTED = new Color(0x9c, 0xa3, 0xaf);

	private PlaceholderFrames() {
	}

	/**
	 * Renders a centred two-line notice.
	 *
	 * @param width frame width in pixels
	 * @param height frame height in pixels
	 * @param title the headline, drawn large
	 * @param detail the second line, drawn small and dimmer; may be null
	 * @param accent true to draw the headline in the accent colour
	 * @return ARGB pixels, {@code width * height} long
	 */
	/**
	 * A solid black frame, the face of a screen that is switched off.
	 *
	 * @param width frame width in pixels
	 * @param height frame height in pixels
	 * @return the pixels, all opaque black
	 */
	public static int[] black(int width, int height) {
		int[] pixels = new int[width * height];

		java.util.Arrays.fill(pixels, 0xFF000000);

		return pixels;
	}

	public static int[] notice(int width, int height, String title, String detail, boolean accent) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();

		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		graphics.setColor(BACKGROUND);
		graphics.fillRect(0, 0, width, height);

		graphics.setColor(ACCENT);
		graphics.fillRect(0, 0, width, 3);

		int titleSize = Math.max(12, Math.min(34, height / 7));
		graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, titleSize));
		graphics.setColor(accent ? ACCENT : TEXT);
		center(graphics, title, width, height / 2 - (detail == null ? 0 : titleSize / 2));

		if (detail != null) {
			int detailSize = Math.max(10, titleSize * 2 / 3);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, detailSize));
			graphics.setColor(MUTED);
			center(graphics, detail, width, height / 2 + titleSize);
		}

		graphics.dispose();

		int[] pixels = new int[width * height];
		image.getRGB(0, 0, width, height, pixels, 0, width);

		return pixels;
	}

	private static void center(Graphics2D graphics, String text, int width, int baseline) {
		int textWidth = graphics.getFontMetrics().stringWidth(text);

		graphics.drawString(text, Math.max(4, (width - textWidth) / 2), baseline);
	}
}
