package dev.belikhun.luna.core.api.string;

import java.util.List;
import java.util.Locale;

public final class CommandCompletions {
	private CommandCompletions() {
	}

	public static List<String> filterPrefix(List<String> values, String input) {
		String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
		return values.stream()
			.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
			.sorted()
			.toList();
	}
}
