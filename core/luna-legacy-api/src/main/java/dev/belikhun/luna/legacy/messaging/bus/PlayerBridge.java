package dev.belikhun.luna.legacy.messaging.bus;

import java.util.Collection;
import java.util.UUID;

/**
 * Everything the messaging bus needs from Minecraft, and nothing else.
 *
 * The bus, both AMQP transports and the channel provider are otherwise entirely
 * platform-free; measured against the modern build, five operations were all
 * that tied 814 lines to one server version. Naming them here is what lets the
 * whole trunk live in the legacy api and the 1.12.2 mod supply a page of glue.
 *
 * The methods are deliberately the *narrow* forms. A wider seam - handing over
 * the server, or the player list - would let the trunk reach for whatever it
 * liked and put MCP names back into shared code, which is the thing this exists
 * to prevent.
 */
public interface PlayerBridge<P> {
	/** The player's account id. */
	UUID idOf(P player);

	/** The player's name, for logs and for the AMQP envelope. */
	String nameOf(P player);

	/** Online player with this id, or null. */
	P byId(UUID id);

	/** Online player with this name, or null. */
	P byName(String name);

	/**
	 * Everyone currently online.
	 *
	 * The vault needs it to pick a carrier: its RPCs ride a player's connection, so
	 * with nobody online there is no route to the proxy and the call has to fail
	 * rather than hang.
	 */
	Collection<P> online();

	/**
	 * Run on the server thread.
	 *
	 * AMQP deliveries arrive on the client library's own thread and listeners
	 * expect the server's, so every inbound message crosses here.
	 */
	void onServerThread(Runnable task);
}
