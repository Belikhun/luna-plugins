package dev.belikhun.luna.core.api.string;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class Formatters {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final Gson GSON = new Gson();
	private static final String PIXEL_ADJUSTER = "\u200C";
	private static final String GLYPH_WIDTHS_RESOURCE = "font/glyph-widths.json";
	private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");
	private static final Map<Integer, Integer> GLYPH_WIDTHS = loadGlyphWidths();
	private static final int DEFAULT_GLYPH_WIDTH = 5;

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
		.withZone(ZoneId.systemDefault());

	private Formatters() {
	}

	public static String number(double value) {
		NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
		format.setGroupingUsed(true);
		format.setMaximumFractionDigits(2);
		return format.format(value);
	}

	public static String money(double value, String currencySymbol) {
		return money(value, currencySymbol, true, "{amount} {symbol}");
	}

	public static String money(double value, String currencySymbol, boolean grouping, String template) {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("vi-VN"));
		symbols.setGroupingSeparator('.');
		symbols.setDecimalSeparator(',');
		DecimalFormat format = new DecimalFormat(grouping ? "#,##0.##" : "0.##", symbols);
		String amount = format.format(value);
		String normalizedSymbol = currencySymbol == null ? "" : currencySymbol;
		String normalizedTemplate = (template == null || template.isBlank()) ? "{amount} {symbol}" : template;
		return normalizedTemplate
			.replace("{amount}", amount)
			.replace("{symbol}", normalizedSymbol);
	}

	public static String date(Instant instant) {
		return DATE_FORMAT.format(instant);
	}

	public static String duration(Duration duration) {
		long totalSeconds = Math.max(0, duration.getSeconds());
		long days = totalSeconds / 86400;
		long hours = (totalSeconds % 86400) / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;

		StringBuilder text = new StringBuilder();
		if (days > 0) {
			text.append(days).append(" ngày ");
		}
		if (hours > 0) {
			text.append(hours).append(" giờ ");
		}
		if (minutes > 0) {
			text.append(minutes).append(" phút ");
		}
		if (seconds > 0 || text.isEmpty()) {
			text.append(seconds).append(" giây");
		}

		return text.toString().trim();
	}

	public static String stripFormats(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}

		String stripped = MINI_MESSAGE.stripTags(value);
		return LEGACY_COLOR_PATTERN.matcher(stripped).replaceAll("");
	}

	public static int minecraftWidth(String value) {
		return minecraftWidth(value, false);
	}

	public static int minecraftWidth(String value, boolean bold) {
		String plain = stripFormats(value);
		if (plain.isBlank()) {
			return 0;
		}

		int width = 0;
		int[] codepoints = plain.codePoints().toArray();
		for (int i = 0; i < codepoints.length; i++) {
			int current = codepoints[i];
			int glyph = glyphWidth(current);
			if (bold && current != ' ') {
				glyph += 1;
			}

			width += glyph;
			if (i < codepoints.length - 1) {
				width += 1;
			}
		}

		return width;
	}

	public static String dotLeader(String left, String right, int totalWidthPx) {
		return dotLeader(left, false, right, false, totalWidthPx, ".");
	}

	public static String dotLeader(String left, String right, int totalWidthPx, String dotPattern) {
		return dotLeader(left, false, right, false, totalWidthPx, dotPattern);
	}

	public static String dotLeader(String left, boolean leftBold, String right, boolean rightBold, int totalWidthPx) {
		return dotLeader(left, leftBold, right, rightBold, totalWidthPx, ".");
	}

	public static String dotLeader(String left, boolean leftBold, String right, boolean rightBold, int totalWidthPx, String dotPattern) {
		String leftValue = left == null ? "" : left;
		String rightValue = right == null ? "" : right;
		String dot = (dotPattern == null || dotPattern.isBlank()) ? "." : dotPattern;
		int target = Math.max(0, totalWidthPx);

		StringBuilder leader = new StringBuilder();
		while (combinedWidth(leftValue, leftBold, leader.toString(), false, rightValue, rightBold) < target) {
			leader.append(dot);
		}

		while (leader.length() > 0 && combinedWidth(leftValue, leftBold, leader.toString(), false, rightValue, rightBold) > target) {
			leader.setLength(Math.max(0, leader.length() - dot.length()));
		}

		while (combinedWidth(leftValue, leftBold, leader.toString(), false, rightValue, rightBold) < target) {
			leader.append(PIXEL_ADJUSTER);
			if (combinedWidth(leftValue, leftBold, leader.toString(), false, rightValue, rightBold) >= target) {
				break;
			}

			if (leader.length() > 256) {
				break;
			}
		}

		while (leader.length() > 0 && combinedWidth(leftValue, leftBold, leader.toString(), false, rightValue, rightBold) > target) {
			leader.setLength(leader.length() - 1);
		}

		String result = leader.toString();
		if (result.isEmpty()) {
			return dot.repeat(3);
		}

		if (result.length() < 3) {
			return dot.repeat(3);
		}

		return result;
	}

	private static int combinedWidth(String first, boolean firstBold, String second, boolean secondBold, String third, boolean thirdBold) {
		int width = 0;
		boolean hasFirst = first != null && !first.isBlank();
		boolean hasSecond = second != null && !second.isBlank();
		boolean hasThird = third != null && !third.isBlank();

		if (hasFirst) {
			width += minecraftWidth(first, firstBold);
		}

		if (hasSecond) {
			if (hasFirst) {
				width += 1;
			}
			width += minecraftWidth(second, secondBold);
		}

		if (hasThird) {
			if (hasFirst || hasSecond) {
				width += 1;
			}
			width += minecraftWidth(third, thirdBold);
		}

		return width;
	}

	private static int glyphWidth(int value) {
		Integer direct = GLYPH_WIDTHS.get(value);
		if (direct != null) {
			return direct;
		}

		Integer derived = derivedGlyphWidth(value);
		if (derived != null) {
			return derived;
		}

		return DEFAULT_GLYPH_WIDTH;
	}

	private static Integer derivedGlyphWidth(int codepoint) {
		if (codepoint == 'Đ') {
			return GLYPH_WIDTHS.get((int) 'D');
		}

		if (codepoint == 'đ') {
			return GLYPH_WIDTHS.get((int) 'd');
		}

		String text = new String(Character.toChars(codepoint));
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
		int[] points = normalized.codePoints().toArray();
		for (int point : points) {
			int type = Character.getType(point);
			if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) {
				continue;
			}

			Integer width = GLYPH_WIDTHS.get(point);
			if (width != null) {
				return width;
			}
		}

		return null;
	}

	private static Map<Integer, Integer> loadGlyphWidths() {
		Map<Integer, Integer> widths = buildFallbackGlyphWidths();
		widths.putAll(readPrecomputedGlyphWidths());
		return Map.copyOf(widths);
	}

	private static Map<Integer, Integer> readPrecomputedGlyphWidths() {
		try (InputStream input = Formatters.class.getClassLoader().getResourceAsStream(GLYPH_WIDTHS_RESOURCE)) {
			if (input == null) {
				return Map.of();
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
				JsonObject root = GSON.fromJson(reader, JsonObject.class);
				if (root == null || !root.has("widths") || !root.get("widths").isJsonObject()) {
					return Map.of();
				}

				Map<Integer, Integer> map = new HashMap<>();
				JsonObject widths = root.getAsJsonObject("widths");
				for (Map.Entry<String, JsonElement> entry : widths.entrySet()) {
					if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) {
						continue;
					}

					int codepoint;
					try {
						codepoint = Integer.parseInt(entry.getKey());
					} catch (NumberFormatException ignored) {
						continue;
					}

					map.put(codepoint, entry.getValue().getAsInt());
				}

				return map;
			}
		} catch (IOException | JsonParseException exception) {
			return Map.of();
		}
	}

	private static Map<Integer, Integer> buildFallbackGlyphWidths() {
		Map<Integer, Integer> widths = new HashMap<>();
		for (char ch = 'A'; ch <= 'Z'; ch++) {
			widths.put((int) ch, 5);
		}
		for (char ch = 'a'; ch <= 'z'; ch++) {
			widths.put((int) ch, 5);
		}
		for (char ch = '0'; ch <= '9'; ch++) {
			widths.put((int) ch, 5);
		}

		widths.put((int) ' ', 3);
		widths.put((int) '!', 1);
		widths.put((int) '\'', 1);
		widths.put((int) ',', 1);
		widths.put((int) '.', 1);
		widths.put((int) ':', 1);
		widths.put((int) ';', 1);
		widths.put((int) '|', 1);
		widths.put((int) 'i', 1);
		widths.put((int) 'I', 3);
		widths.put((int) 'l', 2);
		widths.put((int) '[', 3);
		widths.put((int) ']', 3);
		widths.put((int) '(', 3);
		widths.put((int) ')', 3);
		widths.put((int) '{', 3);
		widths.put((int) '}', 3);
		widths.put((int) '"', 3);
		widths.put((int) '`', 2);
		widths.put((int) '*', 3);
		widths.put((int) 't', 4);
		widths.put((int) 'f', 4);
		widths.put((int) 'k', 4);
		widths.put((int) '<', 4);
		widths.put((int) '>', 4);
		widths.put((int) '@', 6);
		widths.put((int) '~', 6);
		widths.put((int) '%', 5);
		widths.put((int) '₫', 6);
		widths.put((int) '€', 6);
		widths.put((int) '$', 6);

		return widths;
	}

}

