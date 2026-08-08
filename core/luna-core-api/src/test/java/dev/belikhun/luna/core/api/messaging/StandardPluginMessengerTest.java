package dev.belikhun.luna.core.api.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardPluginMessengerTest {
	private static final PluginMessageChannel TEST_CHANNEL = PluginMessageChannel.of("test:alpha");

	@Test
	void normalizesBungeeCordAlias() {
		assertEquals(PluginMessageChannel.bungeeCord(), PluginMessageChannel.of("BungeeCord"));
		assertEquals("bungeecord:main", PluginMessageChannel.of("BungeeCord").value());
	}

	@Test
	void rejectsReservedChannelsForIncomingAndOutgoing() {
		StandardPluginMessenger<String, String> messenger = new StandardPluginMessenger<>();
		PluginMessageChannel reserved = PluginMessageChannel.of("minecraft:register");

		assertThrows(ReservedPluginMessageChannelException.class, () -> messenger.registerOutgoingPluginChannel("plugin-a", reserved));
		assertThrows(ReservedPluginMessageChannelException.class, () -> messenger.registerIncomingPluginChannel("plugin-a", reserved, context -> PluginMessageDispatchResult.HANDLED));
	}

	@Test
	void tracksIncomingAndOutgoingRegistrationsPerOwner() {
		StandardPluginMessenger<String, String> messenger = new StandardPluginMessenger<>();
		PluginMessageHandler<String> handler = context -> PluginMessageDispatchResult.HANDLED;

		PluginMessageListenerRegistration<String, String> registration = messenger.registerIncomingPluginChannel("plugin-a", TEST_CHANNEL, handler);
		messenger.registerOutgoingPluginChannel("plugin-a", TEST_CHANNEL);

		assertTrue(registration.isValid());
		assertTrue(messenger.isIncomingChannelRegistered("plugin-a", TEST_CHANNEL));
		assertTrue(messenger.isOutgoingChannelRegistered("plugin-a", TEST_CHANNEL));
		assertEquals(Set.of(TEST_CHANNEL), messenger.getIncomingChannels("plugin-a"));
		assertEquals(Set.of(TEST_CHANNEL), messenger.getOutgoingChannels("plugin-a"));
		assertEquals(Set.of(TEST_CHANNEL), messenger.getIncomingChannels());
		assertEquals(Set.of(TEST_CHANNEL), messenger.getOutgoingChannels());
		assertEquals(1, messenger.getIncomingChannelRegistrations(TEST_CHANNEL).size());

		messenger.unregisterIncomingPluginChannel("plugin-a", TEST_CHANNEL, handler);
		messenger.unregisterOutgoingPluginChannel("plugin-a", TEST_CHANNEL);

		assertFalse(registration.isValid());
		assertFalse(messenger.isIncomingChannelRegistered("plugin-a", TEST_CHANNEL));
		assertFalse(messenger.isOutgoingChannelRegistered("plugin-a", TEST_CHANNEL));
	}

	@Test
	void rejectsDuplicateIncomingRegistration() {
		StandardPluginMessenger<String, String> messenger = new StandardPluginMessenger<>();
		PluginMessageHandler<String> handler = context -> PluginMessageDispatchResult.HANDLED;

		messenger.registerIncomingPluginChannel("plugin-a", TEST_CHANNEL, handler);
		assertThrows(IllegalArgumentException.class, () -> messenger.registerIncomingPluginChannel("plugin-a", TEST_CHANNEL, handler));
	}

	@Test
	void dispatchesToAllListenersAndAggregatesHandled() {
		List<String> seen = new ArrayList<>();
		StandardPluginMessenger<String, String> messenger = new StandardPluginMessenger<>();
		messenger.registerIncomingPluginChannel("plugin-a", TEST_CHANNEL, context -> {
			seen.add("a:" + context.source() + ":" + context.channel().value());
			return PluginMessageDispatchResult.PASS_THROUGH;
		});
		messenger.registerIncomingPluginChannel("plugin-b", TEST_CHANNEL, context -> {
			seen.add("b:" + context.reader().readUtf());
			return PluginMessageDispatchResult.HANDLED;
		});

		byte[] payload = PluginMessageWriter.create().writeUtf("payload").toByteArray();
		PluginMessageDispatchResult result = messenger.dispatchIncomingMessage("source-player", TEST_CHANNEL, payload);

		assertEquals(PluginMessageDispatchResult.HANDLED, result);
		assertEquals(List.of("a:source-player:test:alpha", "b:payload"), seen);
	}

	@Test
	void continuesDispatchWhenOneListenerThrows() {
		AtomicInteger exceptions = new AtomicInteger();
		AtomicInteger handled = new AtomicInteger();
		StandardPluginMessenger<String, String> messenger = new StandardPluginMessenger<>((registration, throwable) -> exceptions.incrementAndGet());
		messenger.registerIncomingPluginChannel("plugin-a", TEST_CHANNEL, context -> {
			throw new IllegalStateException("boom");
		});
		messenger.registerIncomingPluginChannel("plugin-b", TEST_CHANNEL, context -> {
			handled.incrementAndGet();
			return PluginMessageDispatchResult.HANDLED;
		});

		PluginMessageDispatchResult result = messenger.dispatchIncomingMessage("source", TEST_CHANNEL, new byte[0]);

		assertEquals(1, exceptions.get());
		assertEquals(1, handled.get());
		assertEquals(PluginMessageDispatchResult.HANDLED, result);
	}
}
