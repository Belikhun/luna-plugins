package dev.belikhun.luna.core.fabric.messaging;

public interface FabricAmqpBridge {
	boolean publish(String routingKey, byte[] envelopePayload);

	void setConsumer(FabricAmqpEnvelopeConsumer consumer);

	void clearConsumer();
}
