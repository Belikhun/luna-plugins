package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;

@FunctionalInterface
public interface FabricPlatformOutboundSender {
	boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload);
}
