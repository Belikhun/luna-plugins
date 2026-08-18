package dev.belikhun.luna.core.mc12.forwarding;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import dev.belikhun.luna.core.mc12.reflect.ObfuscatedFields;
import dev.belikhun.luna.legacy.forwarding.ForwardedPlayer;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Install a proxy-supplied identity onto a login in progress.
 *
 * Vanilla's login handler builds the profile itself from the name in the login
 * packet, which behind a proxy is the only thing it has and produces an
 * offline-mode UUID plus no skin. Modern forwarding replaces that, so this
 * writes the real profile in and moves the handler to the state where the next
 * server tick accepts the player.
 *
 * The original login packet is never delivered to vanilla - see
 * {@link ModernForwardingHandler} - which is what makes this race-free. Letting
 * vanilla process the packet and overwriting the profile afterwards would leave
 * a window where the server thread's tick could accept the player under the
 * offline identity, and that window is a tick, not a nanosecond.
 */
final class LoginHandlerAccess {
	/** `NetHandlerLoginServer#loginGameProfile` */
	private static final String[] LOGIN_PROFILE = { "field_147337_i", "loginGameProfile" };

	/** `NetHandlerLoginServer#currentLoginState` */
	private static final String[] LOGIN_STATE = { "field_147328_g", "currentLoginState" };

	/** `NetworkManager#socketAddress` */
	private static final String[] SOCKET_ADDRESS = { "field_150743_l", "socketAddress" };

	/**
	 * The state whose arrival makes the next `update()` tick accept the player.
	 * Enum constants are not remapped, so this name is the same everywhere.
	 */
	private static final String READY_TO_ACCEPT = "READY_TO_ACCEPT";

	private LoginHandlerAccess() {
	}

	/**
	 * Hand the login the forwarded profile and let it proceed.
	 *
	 * @param login the handler mid-login
	 * @param player who the proxy said is connecting, already signature-checked
	 */
	static void acceptForwarded(NetHandlerLoginServer login, ForwardedPlayer player) {
		GameProfile profile = new GameProfile(player.uniqueId(), player.username());

		for (ForwardedPlayer.Property property : player.properties()) {
			// the signature is what lets a client verify a skin came from Mojang;
			// dropping it would leave every forwarded player skinless
			profile.getProperties().put(
				property.name(),
				property.signature() == null
					? new Property(property.name(), property.value())
					: new Property(property.name(), property.value(), property.signature())
			);
		}

		ObfuscatedFields.set(NetHandlerLoginServer.class, login, profile, LOGIN_PROFILE);
		ObfuscatedFields.set(NetHandlerLoginServer.class, login, readyToAccept(), LOGIN_STATE);
	}

	/**
	 * Rewrite the connection's remote address to the player's real one.
	 *
	 * Without this every player appears to connect from the proxy, so an IP ban
	 * hits everyone or no one, and the logs name the wrong host. Best-effort on
	 * purpose: a failure here is a degraded log line, not a reason to refuse a
	 * login that is otherwise fully verified.
	 */
	static void applyRemoteAddress(NetworkManager connection, ForwardedPlayer player, LunaLogger logger) {
		String address = player.address();

		if (address == null || address.isEmpty()) {
			return;
		}

		try {
			SocketAddress current = connection.getRemoteAddress();
			int port = current instanceof InetSocketAddress ? ((InetSocketAddress) current).getPort() : 0;

			ObfuscatedFields.set(
				NetworkManager.class,
				connection,
				new InetSocketAddress(hostOf(address), portOf(address, port)),
				SOCKET_ADDRESS
			);
		} catch (RuntimeException failure) {
			logger.warn("Không ghi được địa chỉ thật của người chơi: " + failure.getMessage());
		}
	}

	/**
	 * Velocity sends the address as it saw it, which for IPv6 is bracketed and
	 * for both families may or may not carry a port.
	 */
	private static String hostOf(String address) {
		if (address.startsWith("[")) {
			int close = address.indexOf(']');

			return close > 0 ? address.substring(1, close) : address;
		}

		int colon = address.indexOf(':');

		// a bare IPv6 address has several colons and no port
		if (colon >= 0 && address.indexOf(':', colon + 1) < 0) {
			return address.substring(0, colon);
		}

		return address;
	}

	private static int portOf(String address, int fallback) {
		int close = address.lastIndexOf(']');
		int colon = address.lastIndexOf(':');

		if (colon < 0 || colon < close) {
			return fallback;
		}

		// bare IPv6: several colons, none of them a port separator
		if (close < 0 && address.indexOf(':') != colon) {
			return fallback;
		}

		try {
			return Integer.parseInt(address.substring(colon + 1));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object readyToAccept() {
		Class<?> stateClass = ObfuscatedFields.find(NetHandlerLoginServer.class, LOGIN_STATE).getType();

		return Enum.valueOf((Class<Enum>) stateClass, READY_TO_ACCEPT);
	}
}
