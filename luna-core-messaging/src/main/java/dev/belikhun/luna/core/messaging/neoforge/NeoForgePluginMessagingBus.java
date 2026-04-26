package dev.belikhun.luna.core.messaging.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NeoForgePluginMessagingBus implements PluginMessageBus<ServerPlayer, ServerPlayer> {
	private static final long DEFAULT_SENDER_WARMUP_WINDOW_MILLIS = 1500L;

	private final LunaLogger logger;
	private final NeoForgeAmqpTransport amqpTransport;
	private final Map<PluginMessageChannel, PluginMessageHandler<ServerPlayer>> incomingHandlers;
	private final Set<PluginMessageChannel> outgoingChannels;
	private final Map<UUID, Long> senderBoundAt;
	private volatile long senderWarmupWindowMillis;

	public NeoForgePluginMessagingBus(LunaLogger logger) {
		this.logger = logger.scope("Bus");
		this.incomingHandlers = new ConcurrentHashMap<>();
		this.outgoingChannels = ConcurrentHashMap.newKeySet();
		this.senderBoundAt = new ConcurrentHashMap<>();
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
		PluginMessageHandler<ServerPlayer> handler = incomingHandlers.get(channel);
		if (handler == null) {
			return PluginMessageDispatchResult.PASS_THROUGH;
		}

		return handler.handle(new PluginMessageContext<>(channel, source, payload));
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<ServerPlayer> handler) {
		incomingHandlers.put(Objects.requireNonNull(channel, "channel"), Objects.requireNonNull(handler, "handler"));
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		if (channel == null) {
			return;
		}

		incomingHandlers.remove(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		outgoingChannels.add(Objects.requireNonNull(channel, "channel"));
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		if (channel == null) {
			return;
		}

		outgoingChannels.remove(channel);
	}

	@Override
	public boolean send(ServerPlayer target, PluginMessageChannel channel, byte[] payload) {
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(payload, "payload");

		if (target == null) {
			return false;
		}

		if (!outgoingChannels.contains(channel)) {
			logger.debug("Bỏ qua gửi plugin message vì channel chưa đăng ký: " + channel);
			return false;
		}

		Long boundAt = senderBoundAt.get(target.getUUID());
		if (boundAt != null && System.currentTimeMillis() - boundAt < senderWarmupWindowMillis) {
			logger.debug("Bỏ qua gửi plugin message vì sender chưa qua warmup: " + target.getGameProfile().getName() + " channel=" + channel);
			return false;
		}

		return amqpTransport.send(target, channel, payload);
	}

	@Override
	public void close() {
		incomingHandlers.clear();
		outgoingChannels.clear();
		senderBoundAt.clear();
		amqpTransport.close();
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
