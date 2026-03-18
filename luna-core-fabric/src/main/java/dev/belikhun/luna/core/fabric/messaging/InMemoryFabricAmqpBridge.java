package dev.belikhun.luna.core.fabric.messaging;

import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryFabricAmqpBridge implements FabricAmqpBridge {

	private final AtomicReference<FabricAmqpEnvelopeConsumer> consumerRef = new AtomicReference<>();

	@Override
	public boolean publish(String routingKey, byte[] envelopePayload) {
		FabricAmqpEnvelopeConsumer consumer = consumerRef.get();
		if (consumer == null) {
			return false;
		}

		consumer.onEnvelope(envelopePayload);
		return true;
	}

	@Override
	public void setConsumer(FabricAmqpEnvelopeConsumer consumer) {
		consumerRef.set(consumer);
	}

	@Override
	public void clearConsumer() {
		consumerRef.set(null);
	}
}
