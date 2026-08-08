package dev.belikhun.luna.core.api.messaging;

import java.util.Collection;

public interface PluginMessageChannelProvider {
	Collection<PluginMessageChannelDescriptor> descriptors();
}
