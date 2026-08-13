package dev.belikhun.luna.legacy.messaging;

import dev.belikhun.luna.legacy.exception.PluginMessagingException;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A plugin message channel name, normalised and validated.
 *
 * The modern api spells this as a record; the rules below are its compact constructor
 * unchanged, because the name is what the proxy matches on and a difference here is a
 * message that silently goes nowhere.
 *
 * Note what the pattern requires: a namespace. 1.12.2's own custom-payload channels are
 * un-namespaced and capped at 20 characters, so none of luna's twelve `luna:` channels
 * is legal on that line's `S3FPacketCustomPayload`. That is survivable only because the
 * transport is a choice - a backend whose channels are all `AMQP` never puts a channel
 * name into a Minecraft packet, since it travels inside the AMQP envelope instead. A
 * legacy backend that ever wants the custom-payload fallback needs a translation layer
 * here and a matching one on the proxy.
 */
public final class PluginMessageChannel {
	private static final String BUNGEE_COMPAT = "BungeeCord";
	private static final String BUNGEE_MAIN = "bungeecord:main";
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[a-z0-9._-]+:[a-z0-9._/-]+$");

	private final String value;

	private PluginMessageChannel(String raw) {
		String normalized = normalize(raw);

		if (Strings.isBlank(normalized)) {
			throw new PluginMessagingException("Plugin message channel không được rỗng.");
		}

		if (!CHANNEL_PATTERN.matcher(normalized).matches()) {
			throw new PluginMessagingException("Plugin message channel không hợp lệ: " + normalized);
		}

		this.value = normalized;
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
		if (path == null) {
			throw new PluginMessagingException("Plugin message channel path không được null.");
		}

		return of("minecraft:" + path.toLowerCase(Locale.ROOT));
	}

	public static PluginMessageChannel bungeeCord() {
		return of(BUNGEE_MAIN);
	}

	public String value() {
		return value;
	}

	public boolean isReserved() {
		return value.equals("minecraft:register") || value.equals("minecraft:unregister");
	}

	public String namespace() {
		return value.substring(0, value.indexOf(':'));
	}

	public String path() {
		return value.substring(value.indexOf(':') + 1);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof PluginMessageChannel)) {
			return false;
		}

		return value.equals(((PluginMessageChannel) other).value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public String toString() {
		return value;
	}
}
