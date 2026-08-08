package dev.belikhun.luna.core.api.messaging;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record PluginMessageChannelDescriptor(
	PluginMessageChannel channel,
	Set<PluginMessageTransportType> transports
) {
	public PluginMessageChannelDescriptor {
		channel = Objects.requireNonNull(channel, "channel");
		if (transports == null || transports.isEmpty()) {
			throw new IllegalArgumentException("transports cannot be empty");
		}

		transports = Set.copyOf(EnumSet.copyOf(transports));
	}

	public static PluginMessageChannelDescriptor of(PluginMessageChannel channel, PluginMessageTransportType transport, PluginMessageTransportType... additional) {
		EnumSet<PluginMessageTransportType> transports = EnumSet.of(Objects.requireNonNull(transport, "transport"), additional);
		return new PluginMessageChannelDescriptor(channel, transports);
	}

	public boolean supports(PluginMessageTransportType transport) {
		return transport != null && transports.contains(transport);
	}
}
