package dev.belikhun.luna.legacy.messaging;

@FunctionalInterface
public interface PluginMessageHandler<SOURCE> {
	PluginMessageDispatchResult handle(PluginMessageContext<SOURCE> context);
}
