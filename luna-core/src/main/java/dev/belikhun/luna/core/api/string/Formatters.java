package dev.belikhun.luna.core.api.string;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Formatters {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");

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
}
