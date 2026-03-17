package dev.belikhun.luna.vault.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalLong;

public final class VaultMoney {
	public static final int SCALE = 2;
	public static final long MINOR_FACTOR = 100L;

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

	public static double toMajorDouble(long minor) {
		return BigDecimal.valueOf(minor, SCALE).doubleValue();
	}

	public static String format(long minor, String currencySymbol, boolean grouping, String template) {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("vi-VN"));
		symbols.setGroupingSeparator('.');
		symbols.setDecimalSeparator(',');
		DecimalFormat format = new DecimalFormat(grouping ? "#,##0.00" : "0.00", symbols);
		String amount = format.format(BigDecimal.valueOf(minor, SCALE));
		String normalizedSymbol = currencySymbol == null ? "" : currencySymbol;
		String normalizedTemplate = (template == null || template.isBlank()) ? "{amount}{symbol}" : template;
		return normalizedTemplate
			.replace("{amount}", amount)
			.replace("{symbol}", normalizedSymbol);
	}

	public static String formatDefault(long minor) {
		return format(minor, "₫", true, "{amount}{symbol}");
	}
}
