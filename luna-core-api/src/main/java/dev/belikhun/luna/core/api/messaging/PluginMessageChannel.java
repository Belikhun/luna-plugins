package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PluginMessageChannel(String value) {
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[a-z0-9._-]+:[a-z0-9._/-]+$");

	public PluginMessageChannel {
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

	public static PluginMessageChannel minecraft(String path) {
		Objects.requireNonNull(path, "path");
		return of("minecraft:" + path.toLowerCase(Locale.ROOT));
	}

	public static PluginMessageChannel bungeeCord() {
		return of("bungeecord:main");
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
