package dev.belikhun.luna.core.api.heartbeat;

import java.util.Locale;

public record BackendMetadata(
	String name,
	String displayName,
	String accentColor
) {
	public BackendMetadata sanitize() {
		String sanitizedName = name == null ? "" : name.trim();
		String sanitizedDisplay = displayName == null ? "" : displayName.trim();
		String sanitizedAccent = accentColor == null ? "" : accentColor.trim();
		if (sanitizedDisplay.isBlank()) {
			sanitizedDisplay = sanitizedName;
		}
		return new BackendMetadata(sanitizedName, sanitizedDisplay, sanitizedAccent);
	}

	public String normalizedName() {
		return normalize(name);
	}

	public boolean isBlank() {
		return normalizedName().isBlank();
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
