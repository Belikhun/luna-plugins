package dev.belikhun.luna.core.api.messaging;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

public final class PluginMessageChannelCatalogue {
	private PluginMessageChannelCatalogue() {
	}

	public static Set<PluginMessageChannelDescriptor> descriptors() {
		Map<PluginMessageChannel, Set<PluginMessageTransportType>> merged = new LinkedHashMap<>();
		for (PluginMessageChannelProvider provider : ServiceLoader.load(PluginMessageChannelProvider.class)) {
			Collection<PluginMessageChannelDescriptor> provided = provider.descriptors();
			if (provided == null) {
				continue;
			}

			for (PluginMessageChannelDescriptor descriptor : provided) {
				if (descriptor == null) {
					continue;
				}

				merged.computeIfAbsent(descriptor.channel(), ignored -> new LinkedHashSet<>()).addAll(descriptor.transports());
			}
		}

		Set<PluginMessageChannelDescriptor> descriptors = new LinkedHashSet<>();
		for (Map.Entry<PluginMessageChannel, Set<PluginMessageTransportType>> entry : merged.entrySet()) {
			descriptors.add(new PluginMessageChannelDescriptor(entry.getKey(), entry.getValue()));
		}
		return Set.copyOf(descriptors);
	}

	public static Set<PluginMessageChannel> channelsFor(PluginMessageTransportType transport) {
		Set<PluginMessageChannel> channels = new LinkedHashSet<>();
		for (PluginMessageChannelDescriptor descriptor : descriptors()) {
			if (descriptor.supports(transport)) {
				channels.add(descriptor.channel());
			}
		}
		return Set.copyOf(channels);
	}
}
