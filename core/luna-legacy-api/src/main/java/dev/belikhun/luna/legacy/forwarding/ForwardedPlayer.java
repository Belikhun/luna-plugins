package dev.belikhun.luna.legacy.forwarding;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who the proxy says is connecting, once the signature has been checked.
 *
 * This is the whole point of forwarding: without it a backend behind a proxy
 * sees every player arriving from the proxy's own address with an offline-mode
 * UUID derived from their name, so bans, permissions and any per-player data
 * key off the wrong identity and the skin is missing.
 */
public final class ForwardedPlayer {
	private final int version;
	private final String address;
	private final UUID uniqueId;
	private final String username;
	private final List<Property> properties;

	public ForwardedPlayer(int version, String address, UUID uniqueId, String username, List<Property> properties) {
		this.version = version;
		this.address = address == null ? "" : address;
		this.uniqueId = uniqueId;
		this.username = username == null ? "" : username;
		this.properties = Collections.unmodifiableList(
			new ArrayList<Property>(properties == null ? new ArrayList<Property>() : properties)
		);
	}

	/** Which forwarding revision the proxy answered with. */
	public int version() {
		return version;
	}

	/** The player's real remote address, as the proxy saw it. */
	public String address() {
		return address;
	}

	public UUID uniqueId() {
		return uniqueId;
	}

	public String username() {
		return username;
	}

	/** Signed profile properties; `textures` is the skin. */
	public List<Property> properties() {
		return properties;
	}

	@Override
	public String toString() {
		return username + "/" + uniqueId + " từ " + address;
	}

	/** One profile property: a name, a value, and Mojang's signature over it. */
	public static final class Property {
		private final String name;
		private final String value;
		private final String signature;

		public Property(String name, String value, String signature) {
			this.name = name;
			this.value = value;
			this.signature = signature;
		}

		public String name() {
			return name;
		}

		public String value() {
			return value;
		}

		/** Null when unsigned, which is normal for an offline-mode network. */
		public String signature() {
			return signature;
		}
	}
}
