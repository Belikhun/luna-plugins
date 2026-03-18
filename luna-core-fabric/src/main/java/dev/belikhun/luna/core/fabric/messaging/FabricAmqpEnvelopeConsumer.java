package dev.belikhun.luna.core.fabric.messaging;

@FunctionalInterface
public interface FabricAmqpEnvelopeConsumer {
	void onEnvelope(byte[] envelopePayload);
}
