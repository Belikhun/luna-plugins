package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.api.messaging.PluginMessageListenerRegistration;
import dev.belikhun.luna.core.api.messaging.StandardPluginMessenger;
import dev.belikhun.luna.core.api.exception.PluginMessagingException;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;

public final class NeoForgePluginMessagingBus implements PluginMessageBus<ServerPlayer, ServerPlayer> {
	private static final long DEFAULT_SENDER_WARMUP_WINDOW_MILLIS = 1500L;

	private final LunaLogger logger;
	private final NeoForgeAmqpTransport amqpTransport;
	private final StandardPluginMessenger<Object, ServerPlayer> messenger;
	private final java.util.Map<UUID, Long> senderBoundAt;
	private volatile long senderWarmupWindowMillis;

	public NeoForgePluginMessagingBus(LunaLogger logger) {
		this.logger = logger.scope("Bus");
		this.messenger = new StandardPluginMessenger<>((registration, throwable) -> this.logger.warn(
			"Listener owner=" + registration.getOwner() + " ném lỗi khi xử lý plugin message channel=" + registration.getChannel() + ": " + throwable.getMessage()
		));
		this.senderBoundAt = new java.util.concurrent.ConcurrentHashMap<>();
		this.senderWarmupWindowMillis = DEFAULT_SENDER_WARMUP_WINDOW_MILLIS;
		this.amqpTransport = createAmqpTransport();
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

	public PluginMessageDispatchResult dispatchIncoming(ServerPlayer source, PluginMessageChannel channel, byte[] payload) {
		return dispatchIncomingMessage(source, channel, payload);
	}

	@Override
	public PluginMessageListenerRegistration<Object, ServerPlayer> registerIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<ServerPlayer> handler) {
		PluginMessageChannel safeChannel = Objects.requireNonNull(channel, "channel");
		boolean shouldRegisterTransport = messenger.getIncomingChannelRegistrations(safeChannel).isEmpty();
		PluginMessageListenerRegistration<Object, ServerPlayer> registration = messenger.registerIncomingPluginChannel(owner, safeChannel, handler);
		if (shouldRegisterTransport) {
			amqpTransport.registerIncoming(safeChannel, this::dispatchIncomingContext);
		}
		return registration;
	}

	@Override
	public void unregisterIncomingPluginChannel(Object owner, PluginMessageChannel channel, PluginMessageHandler<ServerPlayer> handler) {
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
		for (PluginMessageChannel channel : messenger.getIncomingChannels()) {
			amqpTransport.unregisterIncoming(channel);
		}

		for (PluginMessageChannel channel : messenger.getOutgoingChannels()) {
			amqpTransport.unregisterOutgoing(channel);
		}

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
			logger.debug("Bỏ qua gửi plugin message vì sender chưa qua warmup: " + target.getGameProfile().getName() + " channel=" + channel);
			return false;
		}

		if (NeoForgePayloadFallbackTransport.supports(channel) && NeoForgePayloadFallbackTransport.send(target, channel, payload)) {
			return true;
		}

		return amqpTransport.send(target, channel, payload);
	}

	@Override
	public void close() {
		NeoForgePayloadFallbackTransport.deactivate(this);
		clear();
		senderBoundAt.clear();
		amqpTransport.close();
	}

	private PluginMessageDispatchResult dispatchIncomingContext(PluginMessageContext<ServerPlayer> context) {
		return messenger.dispatchIncomingMessage(context.source(), context.channel(), context.payload());
	}

	private NeoForgeAmqpTransport createAmqpTransport() {
		ServiceLoader<NeoForgeAmqpClientProvider> loader = ServiceLoader.load(NeoForgeAmqpClientProvider.class);
		NeoForgeAmqpClientProvider selectedProvider = loader.stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(NeoForgeAmqpClientProvider::priority).reversed())
			.findFirst()
			.orElse(null);

		if (selectedProvider == null) {
			logger.audit("Không tìm thấy AMQP provider cho NeoForge, chuyển sang no-op transport.");
			return new NoopNeoForgeAmqpTransport();
		}

		try {
			NeoForgeAmqpTransport transport = selectedProvider.create(this, logger.scope("Amqp/" + selectedProvider.name()));
			logger.audit("Đã chọn AMQP provider: " + selectedProvider.name());
			return transport == null ? new NoopNeoForgeAmqpTransport() : transport;
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo AMQP provider " + selectedProvider.name() + ". Dùng no-op transport.", exception);
			return new NoopNeoForgeAmqpTransport();
		}
	}
}
