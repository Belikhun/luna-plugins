package dev.belikhun.luna.legacy.messaging;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class PluginMessageChannelDescriptor {
	private final PluginMessageChannel channel;
	private final Set<PluginMessageTransportType> transports;

	/**
	 * A compact constructor on the modern line; the same checks, moved into the
	 * canonical one. The copy is what makes the set immutable and what stops a
	 * caller mutating a descriptor after it is registered.
	 */
	public PluginMessageChannelDescriptor(PluginMessageChannel channel, Set<PluginMessageTransportType> transports) {
		if (transports == null || transports.isEmpty()) {
			throw new IllegalArgumentException("transports cannot be empty");
		}

		this.channel = Objects.requireNonNull(channel, "channel");
		this.transports = Collections.unmodifiableSet(EnumSet.copyOf(transports));
	}

	public PluginMessageChannel channel() {
		return channel;
	}

	public Set<PluginMessageTransportType> transports() {
		return transports;
	}

	public static PluginMessageChannelDescriptor of(PluginMessageChannel channel, PluginMessageTransportType transport, PluginMessageTransportType... additional) {
		EnumSet<PluginMessageTransportType> transports = EnumSet.of(Objects.requireNonNull(transport, "transport"), additional);
		return new PluginMessageChannelDescriptor(channel, transports);
	}

	public boolean supports(PluginMessageTransportType transport) {
		return transport != null && transports.contains(transport);
	}
}
