package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyAdapterRegistry;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyNetworkingAdapter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class FabricFallbackPluginMessagingTransport implements FabricMessagingTransport {

	private final LunaLogger logger;
	private final boolean loggingEnabled;
	private final FabricFamilyAdapterRegistry adapterRegistry;
	private final Supplier<FabricVersionFamily> familySupplier;
	private final Map<PluginMessageChannel, PluginMessageHandler<FabricMessageSource>> incomingHandlers = new ConcurrentHashMap<>();
	private final Set<PluginMessageChannel> outgoingChannels = ConcurrentHashMap.newKeySet();
	private final Set<FabricVersionFamily> boundFamilies = ConcurrentHashMap.newKeySet();

	public FabricFallbackPluginMessagingTransport(
		LunaLogger logger,
		boolean loggingEnabled,
		FabricFamilyAdapterRegistry adapterRegistry,
		Supplier<FabricVersionFamily> familySupplier
	) {
		this.logger = logger.scope("FabricFallback");
		this.loggingEnabled = loggingEnabled;
		this.adapterRegistry = adapterRegistry;
		this.familySupplier = familySupplier;
	}

	@Override
	public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<FabricMessageSource> handler) {
		incomingHandlers.put(channel, handler);
	}

	@Override
	public void unregisterIncoming(PluginMessageChannel channel) {
		incomingHandlers.remove(channel);
	}

	@Override
	public void registerOutgoing(PluginMessageChannel channel) {
		outgoingChannels.add(channel);
	}

	@Override
	public void unregisterOutgoing(PluginMessageChannel channel) {
		outgoingChannels.remove(channel);
	}

	@Override
	public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		if (target == null || !outgoingChannels.contains(channel)) {
			return false;
		}

		FabricVersionFamily family = familySupplier.get();
		FabricFamilyNetworkingAdapter adapter = adapterRegistry.get(family).orElse(null);
		if (adapter == null) {
			if (loggingEnabled) {
				logger.warn("Không có adapter fallback cho family " + family.id());
			}
			return false;
		}

		if (boundFamilies.add(family)) {
			adapter.setIncomingMessageConsumer(this::dispatchIncoming);
		}

		if (loggingEnabled) {
			logger.debug("[FALLBACK:STUB] Send " + channel + " to target=" + target + " on family=" + family.id());
		}

		return adapter.sendPluginMessage(target, channel, payload);
	}

	public void dispatchIncoming(FabricMessageSource source, PluginMessageChannel channel, byte[] payload) {
		PluginMessageHandler<FabricMessageSource> handler = incomingHandlers.get(channel);
		if (handler != null) {
			handler.handle(new PluginMessageContext<>(channel, source, payload));
		}
	}

	@Override
	public void close() {
		boundFamilies.clear();
		incomingHandlers.clear();
		outgoingChannels.clear();
	}
}
