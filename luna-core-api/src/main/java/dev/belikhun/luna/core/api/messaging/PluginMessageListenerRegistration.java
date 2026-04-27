package dev.belikhun.luna.core.api.messaging;

import java.util.Objects;

public final class PluginMessageListenerRegistration<OWNER, SOURCE> {
	private final PluginMessenger<OWNER, SOURCE> messenger;
	private final OWNER owner;
	private final PluginMessageChannel channel;
	private final PluginMessageHandler<SOURCE> handler;

	public PluginMessageListenerRegistration(
		PluginMessenger<OWNER, SOURCE> messenger,
		OWNER owner,
		PluginMessageChannel channel,
		PluginMessageHandler<SOURCE> handler
	) {
		this.messenger = Objects.requireNonNull(messenger, "messenger");
		this.owner = Objects.requireNonNull(owner, "owner");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.handler = Objects.requireNonNull(handler, "handler");
	}

	public PluginMessenger<OWNER, SOURCE> getMessenger() {
		return messenger;
	}

	public OWNER getOwner() {
		return owner;
	}

	public PluginMessageChannel getChannel() {
		return channel;
	}

	public PluginMessageHandler<SOURCE> getHandler() {
		return handler;
	}

	public boolean isValid() {
		return messenger.isRegistrationValid(this);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof PluginMessageListenerRegistration<?, ?> that)) {
			return false;
		}

		return messenger.equals(that.messenger)
			&& owner.equals(that.owner)
			&& channel.equals(that.channel)
			&& handler.equals(that.handler);
	}

	@Override
	public int hashCode() {
		return Objects.hash(messenger, owner, channel, handler);
	}
}
