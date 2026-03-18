package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class QueuedPlatformMessagingBridge implements FabricPlatformMessagingBridge {

	private record OutboundPacket(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
	}

	private final Supplier<FabricMessageSource> sourceSupplier;
	private final AtomicReference<FabricPlatformIncomingReceiver> receiverRef = new AtomicReference<>();
	private final AtomicReference<FabricPlatformOutboundSender> outboundSenderRef = new AtomicReference<>();
	private final Queue<OutboundPacket> pendingOutbound = new ConcurrentLinkedQueue<>();

	public QueuedPlatformMessagingBridge(Supplier<FabricMessageSource> sourceSupplier) {
		this.sourceSupplier = Objects.requireNonNull(sourceSupplier, "sourceSupplier");
	}

	@Override
	public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		OutboundPacket packet = new OutboundPacket(target, channel, payload);
		FabricPlatformOutboundSender sender = outboundSenderRef.get();
		if (sender != null && sender.send(target, channel, payload)) {
			return true;
		}

		pendingOutbound.add(packet);
		return true;
	}

	public void setOutboundSender(FabricPlatformOutboundSender sender) {
		outboundSenderRef.set(sender);
		flushPendingOutbound();
	}

	public void clearOutboundSender() {
		outboundSenderRef.set(null);
	}

	public int flushPendingOutbound() {
		FabricPlatformOutboundSender sender = outboundSenderRef.get();
		if (sender == null) {
			return 0;
		}

		int delivered = 0;
		while (true) {
			OutboundPacket packet = pendingOutbound.peek();
			if (packet == null) {
				break;
			}

			if (!sender.send(packet.target(), packet.channel(), packet.payload())) {
				break;
			}

			pendingOutbound.poll();
			delivered++;
		}
		return delivered;
	}

	public int pendingOutboundCount() {
		return pendingOutbound.size();
	}

	public void injectIncoming(PluginMessageChannel channel, byte[] payload) {
		FabricPlatformIncomingReceiver receiver = receiverRef.get();
		if (receiver != null) {
			receiver.onIncoming(sourceSupplier.get(), channel, payload);
		}
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
