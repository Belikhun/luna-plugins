package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMessengerCommandServiceTest {

	@Test
	void validatesDirectMessageInput() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus);
		gateway.registerChannels();
		FabricMessengerCommandService service = new FabricMessengerCommandService(gateway);

		FabricMessengerCommandService.CommandResult missingTarget = service.sendDirect(UUID.randomUUID(), "Sender", "hub", "", "Hi");
		FabricMessengerCommandService.CommandResult missingContent = service.sendDirect(UUID.randomUUID(), "Sender", "hub", "Alice", "");

		assertFalse(missingTarget.success());
		assertFalse(missingContent.success());
	}

	@Test
	void sendsChatAndReturnsSuccessResult() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus);
		gateway.registerChannels();
		FabricMessengerCommandService service = new FabricMessengerCommandService(gateway);

		FabricMessengerCommandService.CommandResult result = service.sendChat(UUID.randomUUID(), "Sender", "hub", "Xin chao");

		assertTrue(result.success());
		assertTrue(bus.outgoing.contains(MessengerChannels.COMMAND));
	}

	@Test
	void forwardsDirectTargetSuggestions() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus);
		gateway.registerChannels();
		FabricMessengerCommandService service = new FabricMessengerCommandService(gateway);

		bus.injectPresencePlayerNames(Map.of(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob"), gateway);
		Collection<String> suggestions = service.suggestDirectTargets("A", "Sender");

		assertTrue(suggestions.contains("Alice"));
	}

	private static LunaLogger testLogger() {
		return LunaLogger.forLogger(Logger.getLogger("FabricMessengerCommandServiceTest"), false);
	}

	private static final class FakeBus implements PluginMessageBus<FabricMessageSource, FabricMessageTarget> {
		private final Set<PluginMessageChannel> outgoing = ConcurrentHashMap.newKeySet();
		private final Map<PluginMessageChannel, PluginMessageHandler<FabricMessageSource>> incoming = new ConcurrentHashMap<>();

		@Override
		public void registerIncoming(PluginMessageChannel channel, PluginMessageHandler<FabricMessageSource> handler) {
			incoming.put(channel, handler);
		}

		@Override
		public void unregisterIncoming(PluginMessageChannel channel) {
			incoming.remove(channel);
		}

		@Override
		public void registerOutgoing(PluginMessageChannel channel) {
			outgoing.add(channel);
		}

		@Override
		public void unregisterOutgoing(PluginMessageChannel channel) {
			outgoing.remove(channel);
		}

		@Override
		public boolean send(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
			return true;
		}

		@Override
		public void close() {
			outgoing.clear();
			incoming.clear();
		}

		private void injectPresencePlayerNames(Map<UUID, String> namesById, FabricMessengerGateway gateway) {
			PluginMessageHandler<FabricMessageSource> presenceHandler = incoming.get(MessengerChannels.PRESENCE);
			if (presenceHandler == null) {
				return;
			}
			namesById.forEach((id, name) -> {
				dev.belikhun.luna.core.api.messaging.PluginMessageWriter writer = dev.belikhun.luna.core.api.messaging.PluginMessageWriter.create();
				new dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage(
					dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage.CURRENT_PROTOCOL,
					dev.belikhun.luna.core.api.messenger.MessengerPresenceType.JOIN,
					id,
					name,
					"",
					"hub",
					false
				).writeTo(writer);
				presenceHandler.handle(new dev.belikhun.luna.core.api.messaging.PluginMessageContext<>(
					MessengerChannels.PRESENCE,
					new FabricMessageSource("fabric-test", null, ""),
					writer.toByteArray()
				));
			});
		}
	}
}
