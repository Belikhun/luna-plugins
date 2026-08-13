package dev.belikhun.luna.legacy.string;

import net.kyori.adventure.text.minimessage.MiniMessage;

import dev.belikhun.luna.legacy.config.YamlConfigFile;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turning values into the shapes luna shows people.
 *
 * The modern api's class of this name is much larger. This carries only what
 * the 1.12.2 line actually calls, and grows a method at a time as features
 * arrive - a speculative downgrade of four hundred lines would be four hundred
 * lines to keep in step with an original nothing here reads.
 */
public final class Formatters {
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
		.withZone(ZoneId.systemDefault());

	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	/** Section-sign colour and style codes, which 1.12.2 renders natively. */
	private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)\u00a7[0-9A-FK-ORX]");

	private static final long SECONDS_PER_DAY = 86400L;
	private static final long SECONDS_PER_HOUR = 3600L;
	private static final long SECONDS_PER_MINUTE = 60L;

	private Formatters() {
	}

	/**
	 * A duration as `1d 2h 3m 4s`, dropping the units that are zero.
	 *
	 * Floored at one second, because this labels a countdown and "0s" reads as
	 * finished when it is not. A duration with nothing left to show still gets a
	 * seconds part, so the result is never empty.
	 */
	/**
	 * The readable text inside a string, with all markup removed.
	 *
	 * Both kinds have to go: MiniMessage tags luna wrote, and the legacy section-sign
	 * codes 1.12.2 actually renders. A caller uses this to ask "is there anything here
	 * to show?", and a line that is nothing but colour codes must answer no.
	 */
	public static String stripFormats(String value) {
		if (Strings.isBlank(value)) {
			return "";
		}

		String stripped = MINI_MESSAGE.stripTags(value);

		return LEGACY_COLOR_PATTERN.matcher(stripped).replaceAll("");
	}

	/**
	 * An amount of money, from its minor units.
	 *
	 * Vietnamese grouping on purpose: `.` groups thousands and `,` is the decimal
	 * mark, which is what the rest of the network prints. Money has to read the same
	 * on every backend or one server shows a different currency from the next, so
	 * the locale is fixed here rather than taken from the JVM's default.
	 */
	public static String money(long minorUnits, int scale, String currencySymbol, boolean grouping, String template) {
		String amount = formatAmount(BigDecimal.valueOf(minorUnits, scale), moneyPattern(grouping, scale));
		String normalizedSymbol = currencySymbol == null ? "" : currencySymbol;
		String normalizedTemplate = Strings.isBlank(template) ? "{amount}{symbol}" : template;

		return normalizedTemplate
			.replace("{amount}", amount)
			.replace("{symbol}", normalizedSymbol);
	}

	private static String formatAmount(BigDecimal value, String pattern) {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("vi-VN"));

		symbols.setGroupingSeparator('.');
		symbols.setDecimalSeparator(',');

		return new DecimalFormat(pattern, symbols).format(value);
	}

	private static String moneyPattern(boolean grouping, int scale) {
		StringBuilder builder = new StringBuilder(grouping ? "#,##0" : "0");

		if (scale > 0) {
			builder.append('.');

			for (int index = 0; index < scale; index += 1) {
				builder.append('0');
			}
		}

		return builder.toString();
	}

	/**
	 * The same amount, with the symbol, grouping and template read from config.
	 *
	 * Only the `YamlConfigFile` overloads come across. The modern build has a second
	 * pair over Bukkit's `ConfigStore`, and no mod loader has one.
	 */
	public static String money(YamlConfigFile config, double value) {
		String amount = formatAmount(BigDecimal.valueOf(value), moneyGrouping(config) ? "#,##0.##" : "0.##");
		String template = moneyTemplate(config, "{amount} {symbol}");

		return template
			.replace("{amount}", amount)
			.replace("{symbol}", moneySymbol(config));
	}

	public static String money(YamlConfigFile config, long minorUnits, int scale) {
		return money(minorUnits, scale, moneySymbol(config), moneyGrouping(config), moneyTemplate(config, "{amount}{symbol}"));
	}

	public static String moneySymbol(YamlConfigFile config) {
		return config == null ? "₫" : config.getString("strings.money.currencySymbol", "₫");
	}

	private static boolean moneyGrouping(YamlConfigFile config) {
		return config == null || config.getBoolean("strings.money.grouping", true);
	}

	private static String moneyTemplate(YamlConfigFile config, String fallback) {
		return config == null ? fallback : config.getString("strings.money.format", fallback);
	}

	/** A timestamp as the rest of the network prints it: `dd/MM/yyyy HH:mm`, local. */
	public static String date(Instant instant) {
		return DATE_FORMAT.format(instant);
	}

	/** A duration spelled out in Vietnamese: `2 ngày 3 giờ 4 phút`. */
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

		// `length() == 0` rather than `isEmpty()`: StringBuilder only grew one in 15
		if (seconds > 0 || text.length() == 0) {
			text.append(seconds).append(" giây");
		}

		return text.toString().trim();
	}

	/** `" ".repeat(n)` without Java 11. */
	public static String repeated(String unit, int count) {
		if (unit == null || count <= 0) {
			return "";
		}

		StringBuilder out = new StringBuilder(unit.length() * count);

		for (int index = 0; index < count; index += 1) {
			out.append(unit);
		}

		return out.toString();
	}

	public static String compactDuration(Duration duration) {
		long totalSeconds = Math.max(1L, duration.getSeconds());

		long days = totalSeconds / SECONDS_PER_DAY;
		totalSeconds %= SECONDS_PER_DAY;

		long hours = totalSeconds / SECONDS_PER_HOUR;
		totalSeconds %= SECONDS_PER_HOUR;

		long minutes = totalSeconds / SECONDS_PER_MINUTE;
		long seconds = totalSeconds % SECONDS_PER_MINUTE;

		StringBuilder builder = new StringBuilder();

		if (days > 0) {
			builder.append(days).append("d ");
		}

		if (hours > 0) {
			builder.append(hours).append("h ");
		}

		if (minutes > 0) {
			builder.append(minutes).append("m ");
		}

		if (seconds > 0 || builder.length() == 0) {
			builder.append(seconds).append("s");
		}

		return builder.toString().trim();
	}
}
