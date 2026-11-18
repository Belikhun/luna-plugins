package dev.belikhun.luna.core.velocity.players;

import com.velocitypowered.api.proxy.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The chat channel each player is talking in, as whoever owns chat reports it.
 *
 * LunaCore records chat and answers the console, but the channel a line was
 * sent in is luna-messenger's state: a different jar that may not be installed.
 * So the messenger hands a {@link Provider} to this holder at its own startup,
 * and everything here asks through it. With no provider the answer is "not
 * known", never a default: a line recorded without one keeps a null channel.
 *
 * Long-lived like the record store that owns it, so a LunaCore reload does not
 * drop the messenger's registration.
 */
public final class VelocityChatChannels {
	public static final String NETWORK = "network";
	public static final String SERVER = "server";
	public static final String DIRECT = "direct";

	/**
	 * One player's channel.
	 *
	 * @param type       {@link #NETWORK}, {@link #SERVER} or {@link #DIRECT}
	 * @param targetId   the other side of a direct channel; null otherwise
	 * @param targetName that player's name; null otherwise
	 * @param isolated   whether the player's backend keeps its chat to itself, in
	 *                   which case the network channel reaches that backend only
	 */
	public record Channel(String type, UUID targetId, String targetName, boolean isolated) {
	}

	/**
	 * What a forced change did.
	 *
	 * @param error    why it was refused; null when it went through
	 * @param previous the channel before the call
	 * @param current  the channel after it
	 */
	public record Change(String error, Channel previous, Channel current) {
		public boolean ok() {
			return error == null;
		}

		public boolean changed() {
			return ok() && previous != null && current != null
				&& !(previous.type().equals(current.type()) && java.util.Objects.equals(previous.targetId(), current.targetId()));
		}
	}

	/** Implemented by the plugin that routes chat. */
	public interface Provider {
		/** The channel the player's next chat line goes to. */
		Channel current(Player player);

		/**
		 * Put a player in a channel and tell them so in game.
		 *
		 * @param type   one of the channel type constants
		 * @param target the other player of a direct channel; ignored otherwise
		 * @param actor  who asked, for the audit line
		 */
		Change set(Player player, String type, Player target, String actor);
	}

	private volatile Provider provider;

	/** Register the chat router; the latest registration wins. */
	public void provide(Provider next) {
		this.provider = next;
	}

	/** Drop a registration, unless another provider has replaced it since. */
	public void withdraw(Provider previous) {
		if (this.provider == previous) {
			this.provider = null;
		}
	}

	/** Whether a chat router is registered. */
	public boolean available() {
		return provider != null;
	}

	/** The player's channel, or empty when nothing knows it. */
	public Optional<Channel> current(Player player) {
		Provider current = provider;

		if (current == null || player == null) {
			return Optional.empty();
		}

		try {
			return Optional.ofNullable(current.current(player));
		} catch (RuntimeException | LinkageError failure) {
			// a provider from a jar that was since replaced must not break a login or a chat record
			return Optional.empty();
		}
	}

	/** Force a player's channel; empty when no chat router is registered. */
	public Optional<Change> set(Player player, String type, Player target, String actor) {
		Provider current = provider;

		if (current == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(current.set(player, type, target, actor));
	}

	/** A channel type as the wire and the database spell it, or "" when it is not one. */
	public static String normalizeType(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

		return switch (normalized) {
			case NETWORK, SERVER, DIRECT -> normalized;
			default -> "";
		};
	}
}
