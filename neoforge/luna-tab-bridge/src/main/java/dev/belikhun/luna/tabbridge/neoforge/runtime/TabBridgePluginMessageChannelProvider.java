package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelDescriptor;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelProvider;
import dev.belikhun.luna.core.api.messaging.PluginMessageTransportType;

import java.util.List;

public final class TabBridgePluginMessageChannelProvider implements PluginMessageChannelProvider {
	private static final PluginMessageChannel TAB_BRIDGE_CHANNEL = PluginMessageChannel.of("tab:bridge-6");

	@Override
	public List<PluginMessageChannelDescriptor> descriptors() {
		return List.of(PluginMessageChannelDescriptor.of(TAB_BRIDGE_CHANNEL, PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK));
	}
}
