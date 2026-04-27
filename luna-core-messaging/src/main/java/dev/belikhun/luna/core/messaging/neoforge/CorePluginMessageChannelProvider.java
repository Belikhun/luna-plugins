package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.messaging.CoreHeartbeatMessageChannels;
import dev.belikhun.luna.core.api.messaging.CorePlayerMessageChannels;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelDescriptor;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannelProvider;
import dev.belikhun.luna.core.api.messaging.PluginMessageTransportType;

import java.util.List;

public final class CorePluginMessageChannelProvider implements PluginMessageChannelProvider {
	@Override
	public List<PluginMessageChannelDescriptor> descriptors() {
		return List.of(
			PluginMessageChannelDescriptor.of(CoreHeartbeatMessageChannels.REQUEST_IMMEDIATE_PUBLISH, PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK),
			PluginMessageChannelDescriptor.of(CorePlayerMessageChannels.CHAT_RELAY, PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK),
			PluginMessageChannelDescriptor.of(CoreServerSelectorMessageChannels.OPEN_MENU, PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK),
			PluginMessageChannelDescriptor.of(CoreServerSelectorMessageChannels.CONNECT_REQUEST, PluginMessageTransportType.CUSTOM_PAYLOAD_FALLBACK)
		);
	}
}
