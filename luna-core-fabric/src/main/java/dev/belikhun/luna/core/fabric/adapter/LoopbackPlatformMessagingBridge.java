package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class LoopbackPlatformMessagingBridge implements FabricPlatformMessagingBridge {

	private final Supplier<FabricMessageSource> sourceSupplier;
	private final AtomicReference<FabricPlatformIncomingReceiver> receiverRef = new AtomicReference<>();

	public LoopbackPlatformMessagingBridge(Supplier<FabricMessageSource> sourceSupplier) {
		this.sourceSupplier = Objects.requireNonNull(sourceSupplier, "sourceSupplier");
	}

	@Override
	public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		FabricPlatformIncomingReceiver receiver = receiverRef.get();
		if (receiver == null) {
			return false;
		}

		receiver.onIncoming(sourceSupplier.get(), channel, payload);
		return true;
	}

	@Override
	public void setReceiver(FabricPlatformIncomingReceiver receiver) {
		receiverRef.set(receiver);
	}

	@Override
	public void clearReceiver() {
		receiverRef.set(null);
	}
}
