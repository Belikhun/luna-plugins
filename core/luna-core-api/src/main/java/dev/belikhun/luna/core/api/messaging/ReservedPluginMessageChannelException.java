package dev.belikhun.luna.core.api.messaging;

public final class ReservedPluginMessageChannelException extends IllegalArgumentException {
	public ReservedPluginMessageChannelException(PluginMessageChannel channel) {
		super("Plugin message channel bị dành riêng và không thể đăng ký: " + channel);
	}
}
