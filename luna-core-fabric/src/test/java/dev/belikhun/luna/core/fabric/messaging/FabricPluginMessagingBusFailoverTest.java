package dev.belikhun.luna.core.fabric.messaging;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyAdapterRegistry;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyNetworkingAdapter;
import dev.belikhun.luna.core.fabric.adapter.FabricPlatformIncomingReceiver;
import dev.belikhun.luna.core.fabric.adapter.FabricPlatformMessagingBridge;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FabricPluginMessagingBusFailoverTest {

	private static final PluginMessageChannel TEST_CHANNEL = PluginMessageChannel.of("luna:test");
	private static final FabricMessageTarget TEST_TARGET = FabricMessageTarget.server("lobby");
	private static final byte[] TEST_PAYLOAD = new byte[] { 1, 2, 3 };
	private static final AmqpMessagingConfig ENABLED_AMQP = new AmqpMessagingConfig(
		true,
		"amqp://guest:guest@127.0.0.1:5672/%2F",
		"luna.plugin-messaging",
		"luna.proxy.messaging",
		"luna.backend.",
		"fabric-test",
		5000,
		15
	);

	@Test
	void fallsBackWhenAmqpPublishFails() {
		TestAdapter adapter = new TestAdapter();
		RecordingBridge bridge = new RecordingBridge(new boolean[] { false });
		FabricPluginMessagingBus bus = createBus(adapter, bridge);
		try {
			bus.updateAmqpConfig(ENABLED_AMQP);
			bus.registerOutgoing(TEST_CHANNEL);

			boolean delivered = bus.send(TEST_TARGET, TEST_CHANNEL, TEST_PAYLOAD);
			assertTrue(delivered);
			assertEquals(1, bridge.publishCount.get());
			assertEquals(1, adapter.sendCount.get());
		} finally {
			bus.close();
		}
	}

	@Test
	void skipsFallbackWhenAmqpPublishSucceeds() {
		TestAdapter adapter = new TestAdapter();
		RecordingBridge bridge = new RecordingBridge(new boolean[] { true });
		FabricPluginMessagingBus bus = createBus(adapter, bridge);
		try {
			bus.updateAmqpConfig(ENABLED_AMQP);
			bus.registerOutgoing(TEST_CHANNEL);

			boolean delivered = bus.send(TEST_TARGET, TEST_CHANNEL, TEST_PAYLOAD);
			assertTrue(delivered);
			assertEquals(1, bridge.publishCount.get());
			assertEquals(0, adapter.sendCount.get());
		} finally {
			bus.close();
		}
	}

	@Test
	void resumesAmqpAfterReconnect() {
		TestAdapter adapter = new TestAdapter();
		RecordingBridge bridge = new RecordingBridge(new boolean[] { false, true });
		FabricPluginMessagingBus bus = createBus(adapter, bridge);
		try {
			bus.updateAmqpConfig(ENABLED_AMQP);
			bus.registerOutgoing(TEST_CHANNEL);

			boolean firstDelivered = bus.send(TEST_TARGET, TEST_CHANNEL, TEST_PAYLOAD);
			boolean secondDelivered = bus.send(TEST_TARGET, TEST_CHANNEL, TEST_PAYLOAD);

			assertTrue(firstDelivered);
			assertTrue(secondDelivered);
			assertEquals(2, bridge.publishCount.get());
			assertEquals(1, adapter.sendCount.get());
		} finally {
			bus.close();
		}
	}

	private static FabricPluginMessagingBus createBus(TestAdapter adapter, RecordingBridge bridge) {
		FabricFamilyAdapterRegistry registry = new FabricFamilyAdapterRegistry();
		registry.register(adapter);
		LunaLogger logger = LunaLogger.forLogger(Logger.getLogger("FabricPluginMessagingBusFailoverTest"), false);
		FabricPluginMessagingBus bus = new FabricPluginMessagingBus(registry, () -> FabricVersionFamily.MC1201, logger, false);
		bus.bindAmqpBridge(bridge);
		return bus;
	}

	private static final class TestAdapter implements FabricFamilyNetworkingAdapter {
		private final AtomicInteger sendCount = new AtomicInteger();

		@Override
		public FabricVersionFamily family() {
			return FabricVersionFamily.MC1201;
		}

		@Override
		public String adapterId() {
			return "test";
		}

		@Override
		public void initialize() {
		}

		@Override
		public void shutdown() {
		}

		@Override
		public void bindPlatformBridge(FabricPlatformMessagingBridge bridge) {
		}

		@Override
		public void setIncomingMessageConsumer(FabricPlatformIncomingReceiver receiver) {
		}

		@Override
		public boolean sendPluginMessage(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
			sendCount.incrementAndGet();
			return true;
		}
	}

	private static final class RecordingBridge implements FabricAmqpBridge {
		private final boolean[] outcomes;
		private final AtomicInteger publishCount = new AtomicInteger();

		private RecordingBridge(boolean[] outcomes) {
			this.outcomes = outcomes;
		}

		@Override
		public boolean publish(String routingKey, byte[] envelopePayload) {
			int callIndex = publishCount.getAndIncrement();
			if (callIndex < outcomes.length) {
				return outcomes[callIndex];
			}
			return outcomes[outcomes.length - 1];
		}

		@Override
		public void setConsumer(FabricAmqpEnvelopeConsumer consumer) {
		}

		@Override
		public void clearConsumer() {
		}
	}
}
