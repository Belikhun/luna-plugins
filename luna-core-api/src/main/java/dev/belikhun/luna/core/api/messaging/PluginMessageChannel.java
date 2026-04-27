package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PluginMessageChannel(String value) {
	private static final String BUNGEE_COMPAT = "BungeeCord";
	private static final String BUNGEE_MAIN = "bungeecord:main";
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[a-z0-9._-]+:[a-z0-9._/-]+$");

	public PluginMessageChannel {
		value = normalize(value);
		if (value == null || value.isBlank()) {
			throw new PluginMessagingException("Plugin message channel không được rỗng.");
		}

		if (!CHANNEL_PATTERN.matcher(value).matches()) {
			throw new PluginMessagingException("Plugin message channel không hợp lệ: " + value);
		}
	}

	public static PluginMessageChannel of(String value) {
		return new PluginMessageChannel(value);
	}

	public static String normalize(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim();
		if (normalized.equalsIgnoreCase(BUNGEE_COMPAT) || normalized.equalsIgnoreCase(BUNGEE_MAIN)) {
			return BUNGEE_MAIN;
		}

		return normalized.toLowerCase(Locale.ROOT);
	}

	public static PluginMessageChannel minecraft(String path) {
		Objects.requireNonNull(path, "path");
		return of("minecraft:" + path.toLowerCase(Locale.ROOT));
	}

	public static PluginMessageChannel bungeeCord() {
		return of(BUNGEE_MAIN);
	}

	public boolean isReserved() {
		return value.equals("minecraft:register") || value.equals("minecraft:unregister");
	}

	public String namespace() {
		int splitIndex = value.indexOf(':');
		return value.substring(0, splitIndex);
	}

	public String path() {
		int splitIndex = value.indexOf(':');
		return value.substring(splitIndex + 1);
	}

	@Override
	public String toString() {
		return value;
	}
}
