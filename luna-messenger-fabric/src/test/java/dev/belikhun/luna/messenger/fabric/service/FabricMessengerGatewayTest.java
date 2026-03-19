package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerChannels;
import dev.belikhun.luna.core.api.messenger.MessengerCommandRequest;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceMessage;
import dev.belikhun.luna.core.api.messenger.MessengerPresenceType;
import dev.belikhun.luna.core.api.messenger.MessengerResultMessage;
import dev.belikhun.luna.core.api.messenger.MessengerResultType;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.api.messaging.PluginMessageContext;
import dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult;
import dev.belikhun.luna.core.api.messaging.PluginMessageHandler;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMessengerGatewayTest {

	@Test
	void registersAndUnregistersExpectedChannels() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus);

		gateway.registerChannels();

		assertTrue(bus.outgoing.contains(MessengerChannels.COMMAND));
		assertTrue(bus.incoming.containsKey(MessengerChannels.RESULT));
		assertTrue(bus.incoming.containsKey(MessengerChannels.PRESENCE));

		gateway.close();

		assertFalse(bus.outgoing.contains(MessengerChannels.COMMAND));
		assertFalse(bus.incoming.containsKey(MessengerChannels.RESULT));
		assertFalse(bus.incoming.containsKey(MessengerChannels.PRESENCE));
	}

	@Test
	void updatesPresenceAndSuggestionsFromPresenceMessages() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus);
		gateway.registerChannels();

		UUID aliceId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();

		firePresence(bus, new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.JOIN,
			aliceId,
			"Alice",
			"",
			"lobby",
			false
		));

		firePresence(bus, new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.SWAP,
			senderId,
			"Sender",
			"spawn",
			"survival",
			false
		));

		Collection<String> suggestions = gateway.suggestDirectTargets("A", "Sender");
		assertEquals(Set.of("Alice"), Set.copyOf(suggestions));

		firePresence(bus, new MessengerPresenceMessage(
			MessengerPresenceMessage.CURRENT_PROTOCOL,
			MessengerPresenceType.LEAVE,
			aliceId,
			"Alice",
			"lobby",
			"",
			false
		));

		assertTrue(gateway.suggestDirectTargets("", "Sender").isEmpty());
	}

	@Test
	void storesLatestResultByReceiverAndSendsCommandEnvelope() {
		FakeBus bus = new FakeBus();
		RecordingResolver resolver = new RecordingResolver();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus, resolver, 5_000L, true);
		gateway.registerChannels();

		UUID receiverId = UUID.randomUUID();
		fireResult(bus, new MessengerResultMessage(
			MessengerResultMessage.CURRENT_PROTOCOL,
			UUID.randomUUID(),
			receiverId,
			MessengerResultType.DIRECT_CHAT,
			"<green>Xin chao</green>",
			Map.of("sender", "Alice")
		));

		assertTrue(gateway.latestResults().containsKey(receiverId));

		UUID senderId = UUID.randomUUID();
		boolean sent = gateway.sendDirect(senderId, "Sender", "hub", "Xin chao", "Receiver");
		assertTrue(sent);
		assertEquals(MessengerChannels.COMMAND, bus.lastChannel);
		assertNotNull(bus.lastTarget);
		assertNotNull(resolver.lastRequest);

		MessengerCommandRequest request = decodeRequest(bus.lastPayload);
		assertEquals(MessengerCommandType.SEND_DIRECT, request.commandType());
		assertEquals(senderId, request.senderId());
		assertEquals("Sender", request.senderName());
		assertEquals("hub", request.senderServer());
		assertEquals("resolved:Xin chao", request.argument());
		assertEquals("Receiver", request.resolvedValues().get("target"));
		assertEquals("Receiver", request.resolvedValues().get("target_name"));
	}

	@Test
	void evictsPendingRequestsWhenTimeoutExpires() {
		FakeBus bus = new FakeBus();
		FabricMessengerGateway gateway = new FabricMessengerGateway(testLogger(), bus, new FabricBackendPlaceholderResolver(), 1_000L, true);
		gateway.registerChannels();

		UUID senderId = UUID.randomUUID();
		assertTrue(gateway.sendChat(senderId, "Sender", "hub", "hello"));
		assertEquals(1, gateway.pendingRequests().size());

		FabricMessengerGateway.PendingRequest pending = gateway.pendingRequests().values().iterator().next();
		assertTrue(gateway.collectTimedOutRequests(pending.createdAtEpochMillis() + 500L).isEmpty());
		assertEquals(1, gateway.pendingRequests().size());

		Collection<FabricMessengerGateway.PendingRequest> expired = gateway.collectTimedOutRequests(pending.createdAtEpochMillis() + 1_500L);
		assertEquals(1, expired.size());
		assertEquals(0, gateway.pendingRequests().size());
	}

	private static LunaLogger testLogger() {
		return LunaLogger.forLogger(Logger.getLogger("FabricMessengerGatewayTest"), false);
	}

	private static void firePresence(FakeBus bus, MessengerPresenceMessage message) {
		PluginMessageHandler<FabricMessageSource> handler = bus.incoming.get(MessengerChannels.PRESENCE);
		assertNotNull(handler);
		PluginMessageDispatchResult result = handler.handle(new PluginMessageContext<>(
			MessengerChannels.PRESENCE,
			new FabricMessageSource("fabric-test", null, ""),
			encode(message::writeTo)
		));
		assertEquals(PluginMessageDispatchResult.HANDLED, result);
	}

	private static void fireResult(FakeBus bus, MessengerResultMessage message) {
		PluginMessageHandler<FabricMessageSource> handler = bus.incoming.get(MessengerChannels.RESULT);
		assertNotNull(handler);
		PluginMessageDispatchResult result = handler.handle(new PluginMessageContext<>(
			MessengerChannels.RESULT,
			new FabricMessageSource("fabric-test", null, ""),
			encode(message::writeTo)
		));
		assertEquals(PluginMessageDispatchResult.HANDLED, result);
	}

	private static MessengerCommandRequest decodeRequest(byte[] payload) {
		return MessengerCommandRequest.readFrom(PluginMessageReader.of(payload));
	}

	private static byte[] encode(Consumer<PluginMessageWriter> writer) {
		PluginMessageWriter messageWriter = PluginMessageWriter.create();
		writer.accept(messageWriter);
		return messageWriter.toByteArray();
	}

	private static final class FakeBus implements PluginMessageBus<FabricMessageSource, FabricMessageTarget> {

		private final Set<PluginMessageChannel> outgoing = ConcurrentHashMap.newKeySet();
		private final Map<PluginMessageChannel, PluginMessageHandler<FabricMessageSource>> incoming = new ConcurrentHashMap<>();
		private PluginMessageChannel lastChannel;
		private FabricMessageTarget lastTarget;
		private byte[] lastPayload;

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
			lastTarget = target;
			lastChannel = channel;
			lastPayload = payload;
			return true;
		}

		@Override
		public void close() {
			outgoing.clear();
			incoming.clear();
		}
	}

	private static final class RecordingResolver implements BackendPlaceholderResolver {

		private PlaceholderResolutionRequest lastRequest;

		@Override
		public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
			lastRequest = request;
			Map<String, String> exported = new java.util.LinkedHashMap<>(request.internalValues());
			exported.put("target", request.internalValues().getOrDefault("target_name", ""));
			return new PlaceholderResolutionResult("resolved:" + request.content(), exported);
		}
	}
}
