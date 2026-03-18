package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractFabricFamilyNetworkingAdapter implements FabricFamilyNetworkingAdapter {

	private final AtomicReference<FabricPlatformMessagingBridge> bridgeRef = new AtomicReference<>();
	private final AtomicReference<FabricPlatformIncomingReceiver> receiverRef = new AtomicReference<>();

	@Override
	public void bindPlatformBridge(FabricPlatformMessagingBridge bridge) {
		FabricPlatformMessagingBridge previous = bridgeRef.getAndSet(bridge);
		if (previous != null) {
			previous.clearReceiver();
		}

		FabricPlatformIncomingReceiver receiver = receiverRef.get();
		if (bridge != null && receiver != null) {
			bridge.setReceiver(receiver);
		}
	}

	@Override
	public void setIncomingMessageConsumer(FabricPlatformIncomingReceiver receiver) {
		receiverRef.set(receiver);
		FabricPlatformMessagingBridge bridge = bridgeRef.get();
		if (bridge != null) {
			if (receiver == null) {
				bridge.clearReceiver();
			} else {
				bridge.setReceiver(receiver);
			}
		}
	}

	@Override
	public boolean sendPluginMessage(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		FabricPlatformMessagingBridge bridge = bridgeRef.get();
		if (bridge == null) {
			return false;
		}

		return bridge.send(target, channel, payload);
	}
}
