package dev.belikhun.luna.core.api.messaging;

@FunctionalInterface
public interface PluginMessageHandler<SOURCE> {
	PluginMessageDispatchResult handle(PluginMessageContext<SOURCE> context);
}
