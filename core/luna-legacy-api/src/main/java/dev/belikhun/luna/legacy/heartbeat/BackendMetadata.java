package dev.belikhun.luna.legacy.heartbeat;

import dev.belikhun.luna.legacy.string.Strings;

import java.util.Locale;
import java.util.Objects;

/** How a backend names and presents itself: id, display name, accent colour. */
public final class BackendMetadata {
	private final String name;
	private final String displayName;
	private final String accentColor;
	private final String serverName;

	public BackendMetadata(String name, String displayName, String accentColor, String serverName) {
		this.name = name;
		this.displayName = displayName;
		this.accentColor = accentColor;
		this.serverName = serverName;
	}

	public BackendMetadata(String name, String displayName, String accentColor) {
		this(name, displayName, accentColor, "");
	}

	public String name() {
		return name;
	}

	public String displayName() {
		return displayName;
	}

	public String accentColor() {
		return accentColor;
	}

	public String serverName() {
		return serverName;
	}

	/** Trimmed throughout, with a blank display falling back to the name. */
	public BackendMetadata sanitize() {
		String sanitizedName = Strings.trimmed(name);
		String sanitizedDisplay = Strings.trimmed(displayName);
		String sanitizedAccent = Strings.trimmed(accentColor);
		String sanitizedServerName = Strings.trimmed(serverName);

		if (Strings.isBlank(sanitizedDisplay)) {
			sanitizedDisplay = sanitizedName;
		}

		return new BackendMetadata(sanitizedName, sanitizedDisplay, sanitizedAccent, sanitizedServerName);
	}

	public String normalizedName() {
		return normalize(name);
	}

	public boolean isBlank() {
		return Strings.isBlank(normalizedName());
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}

		return value.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof BackendMetadata)) {
			return false;
		}

		BackendMetadata that = (BackendMetadata) other;

		return Objects.equals(name, that.name)
			&& Objects.equals(displayName, that.displayName)
			&& Objects.equals(accentColor, that.accentColor)
			&& Objects.equals(serverName, that.serverName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, displayName, accentColor, serverName);
	}

	@Override
	public String toString() {
		return "BackendMetadata[name=" + name
			+ ", displayName=" + displayName
			+ ", accentColor=" + accentColor
			+ ", serverName=" + serverName + "]";
	}
}
