package dev.belikhun.luna.vault.api;

import dev.belikhun.luna.core.api.string.Formatters;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalLong;

public final class VaultMoney {
	public static final int SCALE = 2;
	public static final long MINOR_FACTOR = 100L;
	public static final Locale MONEY_LOCALE = Locale.forLanguageTag("vi-VN");
	public static final String DEFAULT_CURRENCY_SYMBOL = "₫";
	public static final String DEFAULT_TEMPLATE = "{amount}{symbol}";

	private VaultMoney() {
	}

	public static OptionalLong parseUserInput(String raw) {
		if (raw == null || raw.isBlank()) {
			return OptionalLong.empty();
		}

		try {
			BigDecimal normalized = new BigDecimal(raw.trim().replace(',', '.')).setScale(SCALE, RoundingMode.UNNECESSARY);
			return OptionalLong.of(toMinor(normalized));
		} catch (RuntimeException exception) {
			return OptionalLong.empty();
		}
	}

	public static long fromDouble(double value) {
		BigDecimal normalized = BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
		return toMinor(normalized);
	}

	public static long toMinor(BigDecimal value) {
		return value.movePointRight(SCALE).longValueExact();
	}

	public static BigDecimal toMajorDecimal(long minor) {
		return BigDecimal.valueOf(minor, SCALE);
	}

	public static double toMajorDouble(long minor) {
		return toMajorDecimal(minor).doubleValue();
	}

	public static String formatAmount(long minor) {
		return formatAmount(minor, true);
	}

	public static String formatAmount(long minor, boolean grouping) {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(MONEY_LOCALE);
		symbols.setGroupingSeparator('.');
		symbols.setDecimalSeparator(',');
		DecimalFormat format = new DecimalFormat(grouping ? "#,##0.00" : "0.00", symbols);
		return format.format(toMajorDecimal(minor));
	}

	public static String format(long minor, String currencySymbol, boolean grouping, String template) {
		return Formatters.money(minor, SCALE, currencySymbol, grouping, template == null || template.isBlank() ? DEFAULT_TEMPLATE : template);
	}

	@Deprecated(forRemoval = false)
	public static String formatDefault(long minor) {
		return format(minor, DEFAULT_CURRENCY_SYMBOL, true, DEFAULT_TEMPLATE);
	}
}
