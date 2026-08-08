package dev.belikhun.luna.core.messaging.fabric;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.api.messaging.PluginMessageListenerRegistration;
import dev.belikhun.luna.core.api.messaging.StandardPluginMessenger;
import dev.belikhun.luna.core.messaging.fabric.amqp.FabricAmqpTransport;
import dev.belikhun.luna.core.messaging.fabric.amqp.NoopFabricAmqpTransport;
import dev.belikhun.luna.core.messaging.fabric.amqp.RabbitMqFabricAmqpTransport;
import dev.belikhun.luna.core.messaging.fabric.payload.PayloadFallbackTransport;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The backend's plugin-message bus on Fabric.
 *
 * Registrations and dispatch are {@link StandardPluginMessenger}'s job; what is
 * platform work is choosing how a message leaves. A channel the player's own
 * connection can carry goes that way, because that is the path velocity reads;
 * anything else, and anything the connection refuses, falls through to the
 * broker.
 */
public final class FabricPluginMessagingBus implements PluginMessageBus<ServerPlayer, ServerPlayer> {
	/**
	 * How long after a player is bound their connection is treated as not ready.
	 *
	 * A backend that answers a join with a plugin message immediately is talking
	 * before velocity has finished wiring the player up, and the message is lost
	 * with no error anywhere. Holding it back for the first moments is cheaper
	 * than diagnosing that.
	 */
	private static final long DEFAULT_SENDER_WARMUP_WINDOW_MILLIS = 1500L;

	private final LunaLogger logger;
	private final BackendIdentity backendIdentity;
	private final boolean loggingEnabled;
	private final FabricAmqpTransport amqpTransport;
	private final StandardPluginMessenger<Object, ServerPlayer> messenger;
	private final Map<UUID, Long> senderBoundAt;
	private volatile long senderWarmupWindowMillis;

	public FabricPluginMessagingBus(LunaLogger logger, BackendIdentity backendIdentity, boolean loggingEnabled) {
		this.logger = logger.scope("Bus");
		this.backendIdentity = backendIdentity;
		this.loggingEnabled = loggingEnabled;
		this.messenger = new StandardPluginMessenger<>((registration, throwable) -> this.logger.warn(
			"Listener owner=" + registration.getOwner()
				+ " ném lỗi khi xử lý plugin message channel=" + registration.getChannel()
				+ ": " + throwable.getMessage()
		));
		this.senderBoundAt = new ConcurrentHashMap<>();
		this.senderWarmupWindowMillis = DEFAULT_SENDER_WARMUP_WINDOW_MILLIS;
		this.amqpTransport = createAmqpTransport();
	}

	/** Point the broker transport at the config the core resolved. */
	public void updateAmqpConfig(AmqpMessagingConfig config) {
		amqpTransport.updateConfig(config == null ? AmqpMessagingConfig.disabled() : config.sanitize());
	}

	public boolean isAmqpActive() {
		return amqpTransport.isActive();
	}

	public void setSenderWarmupWindowMillis(long senderWarmupWindowMillis) {
		this.senderWarmupWindowMillis = Math.max(0L, senderWarmupWindowMillis);
	}

	/** Start the warmup window for a player who has just joined. */
	public void bindSender(ServerPlayer sender) {
		if (sender == null) {
			return;
		}

		senderBoundAt.put(sender.getUUID(), System.currentTimeMillis());
	}

	public void unbindSender(ServerPlayer sender) {
		if (sender == null) {
			return;
		}

		senderBoundAt.remove(sender.getUUID());
	}

	@Override
	public PluginMessageListenerRegistration<Object, ServerPlayer> registerIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<ServerPlayer> handler) {
		return messenger.registerIncomingPluginChannel(owner, Objects.requireNonNull(channel, "channel"), handler);
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<ServerPlayer> handler) {
		messenger.unregisterIncomingPluginChannel(owner, Objects.requireNonNull(channel, "channel"), handler);
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel) {
		messenger.unregisterIncomingPluginChannel(owner, Objects.requireNonNull(channel, "channel"));
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner) {
		messenger.unregisterIncomingPluginChannel(owner);
	}

	@Override
	public void registerOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		messenger.registerOutgoingPluginChannel(owner, Objects.requireNonNull(channel, "channel"));
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner, PluginMessageChannel channel) {
		messenger.unregisterOutgoingPluginChannel(owner, Objects.requireNonNull(channel, "channel"));
	}

	@Override
	public void unregisterOutgoingPluginChannel(Object owner) {
		messenger.unregisterOutgoingPluginChannel(owner);
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
	public Set<PluginMessageListenerRegistration<Object, ServerPlayer>> getIncomingChannelRegistrations(Object owner) {
		return messenger.getIncomingChannelRegistrations(owner);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, ServerPlayer>> getIncomingChannelRegistrations(PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(channel);
	}

	@Override
	public Set<PluginMessageListenerRegistration<Object, ServerPlayer>> getIncomingChannelRegistrations(Object owner, PluginMessageChannel channel) {
		return messenger.getIncomingChannelRegistrations(owner, channel);
	}

	@Override
	public boolean isRegistrationValid(PluginMessageListenerRegistration<Object, ServerPlayer> registration) {
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
	public PluginMessageDispatchResult dispatchIncomingMessage(ServerPlayer source, PluginMessageChannel channel, byte[] payload) {
		return messenger.dispatchIncomingMessage(source, channel, payload);
	}

	@Override
	public void clear() {
		messenger.clear();
	}

	@Override
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(payload, "payload");

		if (target == null) {
			return false;
		}

		if (!messenger.getOutgoingChannels().contains(channel)) {
			throw new PluginMessagingException("Outgoing plugin channel chưa được đăng ký: " + channel.value());
		}

		Long boundAt = senderBoundAt.get(target.getUUID());

		if (boundAt != null && System.currentTimeMillis() - boundAt < senderWarmupWindowMillis) {
			logger.debug("Bỏ qua gửi plugin message vì sender chưa qua warmup: "
				+ target.getScoreboardName() + " channel=" + channel);
			return false;
		}

		if (PayloadFallbackTransport.supports(channel) && PayloadFallbackTransport.send(target, channel, payload)) {
			return true;
		}

		return amqpTransport.send(target, channel, payload);
	}

	@Override
	public void close() {
		PayloadFallbackTransport.deactivate(this);
		clear();
		senderBoundAt.clear();
		amqpTransport.close();
	}

	/**
	 * The broker client is shaded into this jar, so the only way it is absent is
	 * a jar that was repackaged; failing softly there keeps the connection path
	 * working, which is the one velocity actually reads.
	 */
	private FabricAmqpTransport createAmqpTransport() {
		try {
			return new RabbitMqFabricAmqpTransport(messenger::dispatchIncomingMessage, backendIdentity, logger, loggingEnabled);
		} catch (RuntimeException | LinkageError throwable) {
			logger.error("Không thể khởi tạo AMQP transport cho Fabric. Dùng no-op transport.", throwable);
			return new NoopFabricAmqpTransport();
		}
	}
}
