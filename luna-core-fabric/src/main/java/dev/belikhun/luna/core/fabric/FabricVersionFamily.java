package dev.belikhun.luna.core.fabric;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum FabricVersionFamily {
	MC1165("mc1165", "1.16.5"),
	MC1182("mc1182", "1.18.2"),
	MC119X("mc119x", "1.19.x"),
	MC1201("mc1201", "1.20.1"),
	MC121X("mc121x", "1.21.x");

	private final String id;
	private final String displayName;

	FabricVersionFamily(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public static Optional<FabricVersionFamily> fromId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}

		String normalized = id.toLowerCase(Locale.ROOT);
		return Arrays.stream(values())
			.filter(value -> value.id.equals(normalized))
			.findFirst();
	}
}
