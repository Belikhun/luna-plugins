package dev.belikhun.luna.core.api.ui;

import net.kyori.adventure.text.format.TextColor;

/**
 * Centralized, composable MiniMessage progress bar renderer.
 */
public final class LunaProgressBar {
	public enum Layout {
		SPLIT,
		ALL_LEFT,
		ALL_RIGHT
	}

	private static final String DEFAULT_GLYPH = "▋";
	private static final String COLOR_CLOSE_TAG = "</c>";
	private static final BarRenderer DEFAULT_BAR_RENDERER = context -> {
		double percent = clampPercent(context.percent());
		int width = Math.max(1, context.width());
		int filled = Math.max(0, Math.min(width, (int) Math.round((percent / 100D) * width)));
		int empty = Math.max(0, width - filled);

		StringBuilder out = new StringBuilder();
		if (filled > 0) {
			if (context.filledGradientEnabled()) {
				double fillRatio = Math.max(0D, Math.min(1D, (double) filled / (double) width));
				out.append(gradientTag(sliceGradientColors(context.filledGradientColors(), fillRatio)));
				appendRepeated(out, context.glyph(), filled);
				out.append("</gradient>");
			} else {
				out.append(colorTag(context.filledColor()));
				appendRepeated(out, context.glyph(), filled);
				out.append(COLOR_CLOSE_TAG);
			}
		}
		if (empty > 0) {
			out.append(colorTag(context.emptyColor()));
			appendRepeated(out, context.glyph(), empty);
			out.append(COLOR_CLOSE_TAG);
		}
		return out.toString();
	};

	private static final TextRenderer DEFAULT_TEXT_RENDERER = context -> {
		if (context == null || context.text() == null || context.text().isBlank()) {
			return "";
		}
		return colorTag(context.color()) + context.text() + COLOR_CLOSE_TAG;
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
	private String[] filledGradientColors;
	private Layout layout;
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
		this.filledGradientColors = new String[] {LunaPalette.SUCCESS_500, LunaPalette.DANGER_500};
		this.layout = Layout.SPLIT;
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
		String[] filledGradientColors
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
		return filledGradient(new String[] {
			colorOrDefault(startHexColor, LunaPalette.SUCCESS_500),
			colorOrDefault(endHexColor, LunaPalette.DANGER_500)
		});
	}

	public LunaProgressBar filledGradient(String... colors) {
		this.filledGradientEnabled = true;
		if (colors == null || colors.length == 0) {
			this.filledGradientColors = new String[] {LunaPalette.SUCCESS_500, LunaPalette.DANGER_500};
			return this;
		}

		if (colors.length == 1) {
			String safe = colorOrDefault(colors[0], LunaPalette.SUCCESS_500);
			this.filledGradientColors = new String[] {safe, safe};
			return this;
		}

		String[] sanitized = new String[colors.length];
		for (int i = 0; i < colors.length; i++) {
			sanitized[i] = colorOrDefault(colors[i], LunaPalette.NEUTRAL_50);
		}
		this.filledGradientColors = sanitized;
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

	public LunaProgressBar layout(Layout value) {
		this.layout = value == null ? Layout.SPLIT : value;
		return this;
	}

	public LunaProgressBar allLeft() {
		this.layout = Layout.ALL_LEFT;
		return this;
	}

	public LunaProgressBar allRight() {
		this.layout = Layout.ALL_RIGHT;
		return this;
	}

	public LunaProgressBar splitLayout() {
		this.layout = Layout.SPLIT;
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
			filledGradientColors == null ? new String[0] : filledGradientColors.clone()
		));
	}

	public String renderValue() {
		return valueRenderer.render(new TextRenderContext(valueText, resolveValueColor()));
	}

	public String resolveValueColor() {
		if (valueColorFromFilledGradient && filledGradientEnabled) {
			double ratio = clampPercent(progressPercent()) / 100D;
			return lerpHexMultiStop(filledGradientColors, ratio);
		}

		return valueColor;
	}

	public String render() {
		String renderedLabel = labelRenderer.render(new TextRenderContext(label, labelColor));
		String renderedBar = renderBarWithFrame();
		String renderedValue = renderValue();

		StringBuilder out = new StringBuilder();
		switch (layout) {
			case ALL_LEFT -> appendParts(out, renderedLabel, renderedValue, renderedBar);
			case ALL_RIGHT -> appendParts(out, renderedBar, renderedLabel, renderedValue);
			case SPLIT -> appendParts(out, renderedLabel, renderedBar, renderedValue);
		}

		return out.toString();
	}

	private String renderBarWithFrame() {
		StringBuilder out = new StringBuilder();
		if (frameEnabled) {
			out.append(colorTag(frameColor)).append("[").append(COLOR_CLOSE_TAG);
		}
		out.append(renderBar());
		if (frameEnabled) {
			out.append(colorTag(frameColor)).append("]").append(COLOR_CLOSE_TAG);
		}
		return out.toString();
	}

	private static void appendParts(StringBuilder out, String... parts) {
		for (String part : parts) {
			if (part == null || part.isBlank()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(" ");
			}
			out.append(part);
		}
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
		String value = colorOrDefault(hexColor, LunaPalette.NEUTRAL_50);
		return "<c:" + value + ">";
	}

	private static String gradientTag(String[] colors) {
		if (colors == null || colors.length == 0) {
			return "<gradient:" + LunaPalette.SUCCESS_500 + ":" + LunaPalette.DANGER_500 + ">";
		}

		if (colors.length == 1) {
			String safe = colorOrDefault(colors[0], LunaPalette.SUCCESS_500);
			return "<gradient:" + safe + ":" + safe + ">";
		}

		StringBuilder tag = new StringBuilder("<gradient");
		for (String color : colors) {
			tag.append(":").append(colorOrDefault(color, LunaPalette.NEUTRAL_50));
		}
		tag.append(">");
		return tag.toString();
	}

	private static String[] sliceGradientColors(String[] colors, double endRatio) {
		double safeEndRatio = Math.max(0D, Math.min(1D, endRatio));
		if (colors == null || colors.length == 0) {
			return new String[] {
				LunaPalette.SUCCESS_500,
				lerpHexMultiStop(new String[] {LunaPalette.SUCCESS_500, LunaPalette.DANGER_500}, safeEndRatio)
			};
		}

		if (colors.length == 1) {
			String safe = colorOrDefault(colors[0], LunaPalette.SUCCESS_500);
			return new String[] {safe, safe};
		}

		String startColor = lerpHexMultiStop(colors, 0D);
		String endColor = lerpHexMultiStop(colors, safeEndRatio);
		int samples = Math.max(2, colors.length);
		String[] sliced = new String[samples];
		for (int i = 0; i < samples; i++) {
			double t = samples == 1 ? 0D : (double) i / (double) (samples - 1);
			double sourceRatio = t * safeEndRatio;
			sliced[i] = lerpHexMultiStop(colors, sourceRatio);
		}

		sliced[0] = startColor;
		sliced[sliced.length - 1] = endColor;
		return sliced;
	}

	private static void appendRepeated(StringBuilder out, String value, int count) {
		for (int i = 0; i < count; i++) {
			out.append(value);
		}
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

	private static String lerpHex(String startHexColor, String endHexColor, double ratio) {
		double clampedRatio = Math.max(0D, Math.min(1D, ratio));
		TextColor start = parseTextColor(startHexColor, LunaPalette.NEUTRAL_50);
		TextColor end = parseTextColor(endHexColor, LunaPalette.NEUTRAL_50);
		return TextColor.lerp((float) clampedRatio, start, end).asHexString();
	}

	private static String lerpHexMultiStop(String[] colors, double ratio) {
		double clampedRatio = Math.max(0D, Math.min(1D, ratio));
		if (colors == null || colors.length == 0) {
			return LunaPalette.NEUTRAL_50;
		}
		if (colors.length == 1) {
			return colorOrDefault(colors[0], LunaPalette.NEUTRAL_50);
		}

		double position = clampedRatio * (colors.length - 1);
		int low = (int) Math.floor(position);
		int high = (int) Math.ceil(position);
		if (high >= colors.length) {
			high = colors.length - 1;
		}
		if (low < 0) {
			low = 0;
		}

		double localRatio = position - low;
		String start = colorOrDefault(colors[low], LunaPalette.NEUTRAL_50);
		String end = colorOrDefault(colors[high], LunaPalette.NEUTRAL_50);
		return lerpHex(start, end, localRatio);
	}

	private static TextColor parseTextColor(String hexColor, String fallback) {
		String value = colorOrDefault(hexColor, fallback);
		TextColor parsed = TextColor.fromHexString(value);
		if (parsed != null) {
			return parsed;
		}

		TextColor fallbackParsed = TextColor.fromHexString(colorOrDefault(fallback, LunaPalette.NEUTRAL_50));
		if (fallbackParsed != null) {
			return fallbackParsed;
		}

		return TextColor.color(0xFFFFFF);
	}

	private static String colorOrDefault(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value;
	}
}
