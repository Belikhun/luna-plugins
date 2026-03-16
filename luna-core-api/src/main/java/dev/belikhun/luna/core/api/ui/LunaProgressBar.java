package dev.belikhun.luna.core.api.ui;

/**
 * Centralized, composable MiniMessage progress bar renderer.
 */
public final class LunaProgressBar {
	private static final String DEFAULT_GLYPH = "▋";
	private static final BarRenderer DEFAULT_BAR_RENDERER = context -> {
		double percent = clampPercent(context.percent());
		int width = Math.max(1, context.width());
		int filled = Math.max(0, Math.min(width, (int) Math.round((percent / 100D) * width)));
		int empty = Math.max(0, width - filled);

		StringBuilder out = new StringBuilder();
		if (filled > 0) {
			if (context.filledGradientEnabled()) {
				String gradientStart = colorOrDefault(context.filledGradientStartColor(), context.filledColor());
				String gradientEnd = colorOrDefault(context.filledGradientEndColor(), context.filledColor());
				int[] startRgb = parseHexColor(gradientStart);
				int[] endRgb = parseHexColor(gradientEnd);

				for (int i = 0; i < filled; i++) {
					double ratio = width <= 1 ? 1D : ((double) i / (double) (width - 1));
					String color = interpolateHex(startRgb, endRgb, ratio);
					out.append(colorTag(color)).append(context.glyph()).append("</color>");
				}
			} else {
				out.append(colorTag(context.filledColor()));
				for (int i = 0; i < filled; i++) {
					out.append(context.glyph());
				}
				out.append("</color>");
			}
		}
		if (empty > 0) {
			out.append(colorTag(context.emptyColor()));
			for (int i = 0; i < empty; i++) {
				out.append(context.glyph());
			}
			out.append("</color>");
		}
		return out.toString();
	};

	private static final TextRenderer DEFAULT_TEXT_RENDERER = context -> {
		if (context == null || context.text() == null || context.text().isBlank()) {
			return "";
		}
		return colorTag(context.color()) + context.text() + "</color>";
	};

	private double min;
	private double max;
	private double numericValue;
	private int width;
	private String glyph;
	private String filledColor;
	private String emptyColor;
	private String frameColor;
	private boolean frameEnabled;
	private String label;
	private String labelColor;
	private String valueText;
	private String valueColor;
	private boolean valueColorFromFilledGradient;
	private boolean filledGradientEnabled;
	private String filledGradientStartColor;
	private String filledGradientEndColor;
	private BarRenderer barRenderer;
	private TextRenderer labelRenderer;
	private TextRenderer valueRenderer;

	private LunaProgressBar() {
		this.min = 0D;
		this.max = 100D;
		this.numericValue = 0D;
		this.width = 25;
		this.glyph = DEFAULT_GLYPH;
		this.filledColor = LunaPalette.SUCCESS_500;
		this.emptyColor = LunaPalette.NEUTRAL_700;
		this.frameColor = LunaPalette.NEUTRAL_500;
		this.frameEnabled = false;
		this.label = "";
		this.labelColor = LunaPalette.NEUTRAL_300;
		this.valueText = "";
		this.valueColor = LunaPalette.NEUTRAL_50;
		this.valueColorFromFilledGradient = false;
		this.filledGradientEnabled = false;
		this.filledGradientStartColor = LunaPalette.SUCCESS_500;
		this.filledGradientEndColor = LunaPalette.DANGER_500;
		this.barRenderer = DEFAULT_BAR_RENDERER;
		this.labelRenderer = DEFAULT_TEXT_RENDERER;
		this.valueRenderer = DEFAULT_TEXT_RENDERER;
	}

	@FunctionalInterface
	public interface BarRenderer {
		String render(BarRenderContext context);
	}

	@FunctionalInterface
	public interface TextRenderer {
		String render(TextRenderContext context);
	}

	public record BarRenderContext(
		double percent,
		int width,
		String glyph,
		String filledColor,
		String emptyColor,
		boolean filledGradientEnabled,
		String filledGradientStartColor,
		String filledGradientEndColor
	) {
	}

	public record TextRenderContext(
		String text,
		String color
	) {
	}

	public static LunaProgressBar compose() {
		return new LunaProgressBar();
	}

	public LunaProgressBar range(double min, double max) {
		this.min = sanitizeFinite(min);
		this.max = sanitizeFinite(max);
		return this;
	}

	public LunaProgressBar min(double value) {
		this.min = sanitizeFinite(value);
		return this;
	}

	public LunaProgressBar max(double value) {
		this.max = sanitizeFinite(value);
		return this;
	}

	public LunaProgressBar value(double value) {
		this.numericValue = sanitizeFinite(value);
		return this;
	}

	public LunaProgressBar width(int value) {
		this.width = Math.max(1, value);
		return this;
	}

	public LunaProgressBar glyph(String value) {
		this.glyph = value == null || value.isBlank() ? DEFAULT_GLYPH : value;
		return this;
	}

	public LunaProgressBar filledColor(String hexColor) {
		this.filledColor = colorOrDefault(hexColor, LunaPalette.SUCCESS_500);
		this.filledGradientEnabled = false;
		return this;
	}

	public LunaProgressBar filledGradient(String startHexColor, String endHexColor) {
		this.filledGradientEnabled = true;
		this.filledGradientStartColor = colorOrDefault(startHexColor, LunaPalette.SUCCESS_500);
		this.filledGradientEndColor = colorOrDefault(endHexColor, LunaPalette.DANGER_500);
		return this;
	}

	public LunaProgressBar disableFilledGradient() {
		this.filledGradientEnabled = false;
		return this;
	}

	public LunaProgressBar emptyColor(String hexColor) {
		this.emptyColor = colorOrDefault(hexColor, LunaPalette.NEUTRAL_700);
		return this;
	}

	public LunaProgressBar frameColor(String hexColor) {
		this.frameColor = colorOrDefault(hexColor, LunaPalette.NEUTRAL_500);
		return this;
	}

	public LunaProgressBar frame(boolean enabled) {
		this.frameEnabled = enabled;
		return this;
	}

	public LunaProgressBar label(String text) {
		this.label = text == null ? "" : text;
		return this;
	}

	public LunaProgressBar labelColor(String hexColor) {
		this.labelColor = colorOrDefault(hexColor, LunaPalette.NEUTRAL_300);
		return this;
	}

	public LunaProgressBar value(String text) {
		this.valueText = text == null ? "" : text;
		return this;
	}

	public LunaProgressBar valueColor(String hexColor) {
		this.valueColor = colorOrDefault(hexColor, LunaPalette.NEUTRAL_50);
		return this;
	}

	public LunaProgressBar valueColorFromFilledGradient(boolean enabled) {
		this.valueColorFromFilledGradient = enabled;
		return this;
	}

	public LunaProgressBar barRenderer(BarRenderer renderer) {
		this.barRenderer = renderer == null ? DEFAULT_BAR_RENDERER : renderer;
		return this;
	}

	public LunaProgressBar labelRenderer(TextRenderer renderer) {
		this.labelRenderer = renderer == null ? DEFAULT_TEXT_RENDERER : renderer;
		return this;
	}

	public LunaProgressBar valueRenderer(TextRenderer renderer) {
		this.valueRenderer = renderer == null ? DEFAULT_TEXT_RENDERER : renderer;
		return this;
	}

	public String renderBar() {
		return barRenderer.render(new BarRenderContext(
			progressPercent(),
			width,
			glyph,
			filledColor,
			emptyColor,
			filledGradientEnabled,
			filledGradientStartColor,
			filledGradientEndColor
		));
	}

	public String render() {
		StringBuilder out = new StringBuilder();
		String renderedLabel = labelRenderer.render(new TextRenderContext(label, labelColor));
		if (!renderedLabel.isBlank()) {
			out.append(renderedLabel);
			out.append(" ");
		}

		if (frameEnabled) {
			out.append(colorTag(frameColor)).append("[").append("</color>");
		}
		out.append(renderBar());
		if (frameEnabled) {
			out.append(colorTag(frameColor)).append("]").append("</color>");
		}

		String effectiveValueColor = valueColor;
		if (valueColorFromFilledGradient && filledGradientEnabled) {
			double ratio = clampPercent(progressPercent()) / 100D;
			int[] startRgb = parseHexColor(filledGradientStartColor);
			int[] endRgb = parseHexColor(filledGradientEndColor);
			effectiveValueColor = interpolateHex(startRgb, endRgb, ratio);
		}

		String renderedValue = valueRenderer.render(new TextRenderContext(valueText, effectiveValueColor));
		if (!renderedValue.isBlank()) {
			out.append(" ");
			out.append(renderedValue);
		}

		return out.toString();
	}

	public double progressPercent() {
		double safeMin = Math.min(min, max);
		double safeMax = Math.max(min, max);
		double rangeMaxFromZero = Math.max(0D, safeMax);
		if (rangeMaxFromZero <= 0D) {
			return 0D;
		}

		double clamped = clamp(numericValue, safeMin, safeMax);
		double normalized = Math.max(0D, clamped);
		return clampPercent((normalized / rangeMaxFromZero) * 100D);
	}

	private static String colorTag(String hexColor) {
		return "<color:" + colorOrDefault(hexColor, LunaPalette.NEUTRAL_50) + ">";
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clampPercent(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0D;
		}
		return Math.max(0D, Math.min(100D, value));
	}

	private static double sanitizeFinite(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0D;
		}
		return value;
	}

	private static int[] parseHexColor(String hexColor) {
		String sanitized = colorOrDefault(hexColor, LunaPalette.NEUTRAL_50).trim();
		if (sanitized.startsWith("#")) {
			sanitized = sanitized.substring(1);
		}
		if (sanitized.length() != 6) {
			return new int[] {255, 255, 255};
		}

		try {
			int r = Integer.parseInt(sanitized.substring(0, 2), 16);
			int g = Integer.parseInt(sanitized.substring(2, 4), 16);
			int b = Integer.parseInt(sanitized.substring(4, 6), 16);
			return new int[] {r, g, b};
		} catch (NumberFormatException exception) {
			return new int[] {255, 255, 255};
		}
	}

	private static String interpolateHex(int[] startRgb, int[] endRgb, double ratio) {
		double clampedRatio = Math.max(0D, Math.min(1D, ratio));
		int r = (int) Math.round(startRgb[0] + (endRgb[0] - startRgb[0]) * clampedRatio);
		int g = (int) Math.round(startRgb[1] + (endRgb[1] - startRgb[1]) * clampedRatio);
		int b = (int) Math.round(startRgb[2] + (endRgb[2] - startRgb[2]) * clampedRatio);
		return String.format("#%02x%02x%02x", r, g, b);
	}

	private static String colorOrDefault(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value;
	}
}