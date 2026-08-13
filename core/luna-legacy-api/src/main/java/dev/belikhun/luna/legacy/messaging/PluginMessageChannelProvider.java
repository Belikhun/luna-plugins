package dev.belikhun.luna.legacy.messaging;

import java.util.Collection;

public interface PluginMessageChannelProvider {
	Collection<PluginMessageChannelDescriptor> descriptors();
}
