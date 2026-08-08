package dev.belikhun.luna.core.api.heartbeat;

import java.util.Locale;

public record BackendMetadata(
	String name,
	String displayName,
	String accentColor,
	String serverName
) {
	public BackendMetadata(String name, String displayName, String accentColor) {
		this(name, displayName, accentColor, "");
	}

	public BackendMetadata sanitize() {
		String sanitizedName = name == null ? "" : name.trim();
		String sanitizedDisplay = displayName == null ? "" : displayName.trim();
		String sanitizedAccent = accentColor == null ? "" : accentColor.trim();
		String sanitizedServerName = serverName == null ? "" : serverName.trim();
		if (sanitizedDisplay.isBlank()) {
			sanitizedDisplay = sanitizedName;
		}
		return new BackendMetadata(sanitizedName, sanitizedDisplay, sanitizedAccent, sanitizedServerName);
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
