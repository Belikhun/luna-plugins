package dev.belikhun.luna.legacy.messaging.bus;

import dev.belikhun.luna.legacy.exception.PluginMessagingException;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.legacy.messaging.PluginMessageBus;
import dev.belikhun.luna.legacy.messaging.PluginMessageChannel;
import dev.belikhun.luna.legacy.messaging.PluginMessageContext;
import dev.belikhun.luna.legacy.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.legacy.messaging.PluginMessageHandler;
import dev.belikhun.luna.legacy.messaging.PluginMessageListenerRegistration;
import dev.belikhun.luna.legacy.messaging.StandardPluginMessenger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A backend's plugin-message bus: channel bookkeeping, an AMQP transport and an
 * optional payload fallback, over whatever type the platform calls a player.
 *
 * Generic rather than tied to a player class, and given its Minecraft through
 * {@link PlayerBridge}. The modern build names `ServerPlayer` in six places
 * across this file and its transports; parameterising instead is what lets the
 * 1.12.2 mod be a page of glue rather than a second copy of the bus.
 *
 * **Send order is deliberate.** The payload fallback is tried first where it
 * applies, because a message riding the player's own connection reaches the
 * proxy without a broker in the path; AMQP is the general answer underneath it.
 * On 1.12.2 nothing supplies a fallback, so every message is AMQP.
 */
public final class PluginMessagingBus<P> implements PluginMessageBus<P, P> {
	/**
	 * How long after a player is bound their outbound messages are dropped.
	 *
	 * A connection is not ready to carry a plugin message the instant the player
	 * object exists, and a message sent into that window is lost silently. Waiting
	 * it out costs nothing on a join and is the difference between a reliable
	 * handshake and an intermittent one.
	 */
	private static final long DEFAULT_SENDER_WARMUP_WINDOW_MILLIS = 1500L;

	private final LunaLogger logger;
	private final PlayerBridge<P> players;
	private final AmqpTransport<P> amqpTransport;
	private final StandardPluginMessenger<Object, P> messenger;
	private final Map<UUID, Long> senderBoundAt;

	private volatile PayloadFallback<P> payloadFallback;
	private volatile long senderWarmupWindowMillis;

	/**
	 * Builds the transport this bus will own.
	 *
	 * A transport has to dispatch inbound bodies back into its bus, and a bus has
	 * to send through its transport, so one of them must be handed the other after
	 * the fact. Doing it with a factory keeps both fields final and makes it
	 * impossible to pair a transport with a bus it does not belong to - which is
	 * exactly the bug a two-step construction invites.
	 */
	public interface TransportFactory<P> {
		AmqpTransport<P> create(PluginMessagingBus<P> bus);
	}

	public PluginMessagingBus(LunaLogger logger, PlayerBridge<P> players, TransportFactory<P> transports) {
		Objects.requireNonNull(transports, "transports");

		this.logger = logger.scope("Bus");
		this.players = Objects.requireNonNull(players, "players");
		this.messenger = new StandardPluginMessenger<Object, P>((registration, throwable) -> this.logger.warn(
			"Listener owner=" + registration.getOwner()
				+ " ném lỗi khi xử lý plugin message channel=" + registration.getChannel()
				+ ": " + throwable.getMessage()
		));
		this.senderBoundAt = new ConcurrentHashMap<UUID, Long>();
		this.senderWarmupWindowMillis = DEFAULT_SENDER_WARMUP_WINDOW_MILLIS;

		// last, and every other field already set: the factory is handed a fully
		// built bus and is expected only to keep the reference, never to use it here
		this.amqpTransport = Objects.requireNonNull(transports.create(this), "transport");
	}

	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config == null ? AmqpMessagingConfig.disabled() : config.sanitize());
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	public void setSenderWarmupWindowMillis(long senderWarmupWindowMillis) {
		this.senderWarmupWindowMillis = Math.max(0L, senderWarmupWindowMillis);
	}

	public void bindSender(P sender) {
		if (sender == null) {
			return;
		}

		senderBoundAt.put(players.idOf(sender), Long.valueOf(System.currentTimeMillis()));
	}

	public void unbindSender(P sender) {
		if (sender == null) {
			return;
		}

		senderBoundAt.remove(players.idOf(sender));
	}

	public PluginMessageDispatchResult dispatchIncoming(P source, PluginMessageChannel channel, byte[] payload) {
		return dispatchIncomingMessage(source, channel, payload);
	}

	/**
	 * Adopt a loader's payload transport, preferring it to AMQP where it applies.
	 *
	 * Handed in rather than reached for: the transport is the only part of this
	 * class a loader has to supply, and taking it as an interface is what lets one
	 * bus serve every loader.
	 */
	public void useFallback(PayloadFallback<P> fallback) {
		this.payloadFallback = fallback;

		if (fallback != null) {
			fallback.attach(this::dispatchIncoming);
		}
	}

	@Override
	public PluginMessageListenerRegistration<Object, P> registerIncomingOffTick(
		PluginMessageChannel channel,
		PluginMessageHandler<P> handler
	) {
		PluginMessageListenerRegistration<Object, P> registration = registerIncoming(channel, handler);

		amqpTransport.deliverOffTick(Objects.requireNonNull(channel, "channel"));

		return registration;
	}

	@Override
	public PluginMessageListenerRegistration<Object, P> registerIncomingPluginChannel(
		Object owner,
		PluginMessageChannel channel,
		PluginMessageHandler<P> handler
	) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = messenger.getIncomingChannelRegistrations(safeChannel).isEmpty();
		PluginMessageListenerRegistration<Object, P> registration =
			messenger.registerIncomingPluginChannel(owner, safeChannel, handler);

		if (shouldRegisterTransport) {
			amqpTransport.registerIncoming(
				safeChannel,
				context -> messenger.dispatchIncomingMessage(context.source(), context.channel(), context.payload())
			);
		}

		return registration;
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<P> handler) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");

		messenger.unregisterIncomingPluginChannel(owner, safeChannel, handler);

		if (messenger.getIncomingChannelRegistrations(safeChannel).isEmpty()) {
			amqpTransport.unregisterIncoming(safeChannel);
		}
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");

		messenger.unregisterIncomingPluginChannel(owner, safeChannel);

		if (messenger.getIncomingChannelRegistrations(safeChannel).isEmpty()) {
			amqpTransport.unregisterIncoming(safeChannel);
		}
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner) {
		Set<PluginMessageChannel> channels = messenger.getIncomingChannels(owner);

		messenger.unregisterIncomingPluginChannel(owner);

		for (PluginMessageChannel channel : channels) {
			if (messenger.getIncomingChannelRegistrations(channel).isEmpty()) {
				amqpTransport.unregisterIncoming(channel);
			}
		}
	}

	@Override
	public void registerOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = !messenger.getOutgoingChannels().contains(safeChannel);

		messenger.registerOutgoingPluginChannel(owner, safeChannel);

		if (shouldRegisterTransport) {
			amqpTransport.registerOutgoing(safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");

		messenger.unregisterOutgoingPluginChannel(owner, safeChannel);

		if (!messenger.getOutgoingChannels().contains(safeChannel)) {
			amqpTransport.unregisterOutgoing(safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner) {
		Set<PluginMessageChannel> channels = messenger.getOutgoingChannels(owner);

		messenger.unregisterOutgoingPluginChannel(owner);

		for (PluginMessageChannel channel : channels) {
			if (!messenger.getOutgoingChannels().contains(channel)) {
				amqpTransport.unregisterOutgoing(channel);
			}
		}
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels() {
		return messenger.getOutgoingChannels();
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels(Object owner) {
		return messenger.getOutgoingChannels(owner);
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels() {
		return messenger.getIncomingChannels();
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels(Object owner) {
		return messenger.getIncomingChannels(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, P>> getIncomingChannelRegistrations(Object owner) {
		return messenger.getIncomingChannelRegistrations(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, P>> getIncomingChannelRegistrations(PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(channel);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, P>> getIncomingChannelRegistrations(Object owner, PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(owner, channel);
	}

	@Override
	public boolean isRegistrationValid(PluginMessageListenerRegistration<Object, P> registration) {
		return messenger.isRegistrationValid(registration);
	}

	@Override
	public boolean isIncomingChannelRegistered(Object owner, PluginMessageChannel channel) {
		return messenger.isIncomingChannelRegistered(owner, channel);
	}

	@Override
	public boolean isOutgoingChannelRegistered(Object owner, PluginMessageChannel channel) {
		return messenger.isOutgoingChannelRegistered(owner, channel);
	}

	@Override
	public PluginMessageDispatchResult dispatchIncomingMessage(P source, PluginMessageChannel channel, byte[] payload) {
		return messenger.dispatchIncomingMessage(source, channel, payload);
	}

	@Override
	public boolean send(P target, PluginMessageChannel channel, byte[] payload) {
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(payload, "payload");

		if (target == null) {
			return false;
		}

		if (!messenger.getOutgoingChannels().contains(channel)) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + channel.value());
		}

		Long boundAt = senderBoundAt.get(players.idOf(target));

		if (boundAt != null && System.currentTimeMillis() - boundAt.longValue() < senderWarmupWindowMillis) {
			logger.debug("Bỏ qua gửi plugin message vì sender chưa qua warmup: "
				+ players.nameOf(target) + " channel=" + channel);

			return false;
		}

		PayloadFallback<P> fallback = payloadFallback;

		if (fallback != null && fallback.supports(channel) && fallback.send(target, channel, payload)) {
			return true;
		}

		return amqpTransport.send(target, channel, payload);
	}

	@Override
	public void clear() {
		for (PluginMessageChannel channel : messenger.getIncomingChannels()) {
			amqpTransport.unregisterIncoming(channel);
		}

		for (PluginMessageChannel channel : messenger.getOutgoingChannels()) {
			amqpTransport.unregisterOutgoing(channel);
		}

		messenger.clear();
	}

	@Override
	public void close() {
		PayloadFallback<P> fallback = payloadFallback;

		if (fallback != null) {
			fallback.detach();
			payloadFallback = null;
		}

		clear();
		senderBoundAt.clear();
		amqpTransport.close();
	}
}
