package dev.belikhun.luna.core.velocity;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.string.Formatters;

import java.math.BigDecimal;
import java.util.Map;

public record VelocityMoneyFormat(
	String currencySymbol,
	boolean grouping,
	String template
) {
	private static final String DEFAULT_CURRENCY_SYMBOL = "<#FCF0A0>⛃</#FCF0A0>";
	private static final String DEFAULT_TEMPLATE = "<#FFDFD4><b>{amount}</b></#FFDFD4> {symbol}";

	public VelocityMoneyFormat {
		currencySymbol = normalize(currencySymbol, DEFAULT_CURRENCY_SYMBOL);
		template = normalize(template, DEFAULT_TEMPLATE);
	}

	public static VelocityMoneyFormat fromConfig(Map<String, Object> rootConfig) {
		Map<String, Object> strings = ConfigValues.map(rootConfig, "strings");
		Map<String, Object> money = ConfigValues.map(strings, "money");
		return new VelocityMoneyFormat(
			ConfigValues.stringPreserveWhitespace(money.get("currencySymbol"), DEFAULT_CURRENCY_SYMBOL),
			ConfigValues.booleanValue(money.get("grouping"), true),
			ConfigValues.stringPreserveWhitespace(money.get("format"), DEFAULT_TEMPLATE)
		);
	}

	public String format(double value) {
		return Formatters.money(value, currencySymbol, grouping, template);
	}

	public String formatMinor(long minorUnits, int scale) {
		return Formatters.money(minorUnits, scale, currencySymbol, grouping, template);
	}

	private static String normalize(String value, String fallback) {
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		return value;
	}
}
