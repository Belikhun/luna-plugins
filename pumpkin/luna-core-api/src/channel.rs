//! Plugin message channels: their names, and how each one travels.
//!
//! A channel name has to survive the trip between platforms unchanged, so the
//! normalisation here is the JVM's `PluginMessageChannel` rule verbatim: trim,
//! lowercase, and accept only `namespace:path`. The one alias is BungeeCord's,
//! which is spelled two ways in the wild and means one channel.
//!
//! Which transport carries a channel is a static table rather than the JVM's
//! `ServiceLoader` catalogue, because a Pumpkin plugin cannot be several
//! components: the `plugin` world has no inter-plugin imports, so everything
//! luna does ships in one, and a registry assembled at runtime would only be
//! reading back what this file already says.

/// How a channel's messages travel between the backend and the proxy.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transport {
	/// Through the broker, which reaches a backend with nobody on it.
	Amqp,
	/// As a vanilla custom payload on a player's own connection.
	CustomPayload,
}

/// A channel and the transports that carry it.
pub struct ChannelSpec {
	pub channel: &'static str,
	pub transports: &'static [Transport],
}

// Channels the core itself speaks, matching `CorePlayerMessageChannels` and
// `CoreServerSelectorMessageChannels` on the JVM.
pub const CHAT_RELAY: &str = "luna:core_player_chat";
pub const SELECTOR_OPEN: &str = "luna:server_selector_open";
pub const SELECTOR_CONNECT: &str = "luna:server_selector_connect";

/// Every channel luna knows, with how it travels.
pub const CHANNELS: &[ChannelSpec] = &[
	ChannelSpec {
		channel: CHAT_RELAY,
		transports: &[Transport::CustomPayload, Transport::Amqp],
	},
	ChannelSpec {
		channel: SELECTOR_OPEN,
		transports: &[Transport::CustomPayload, Transport::Amqp],
	},
	ChannelSpec {
		channel: SELECTOR_CONNECT,
		transports: &[Transport::CustomPayload, Transport::Amqp],
	},
];

/// Whether a channel is carried on a player's own connection.
///
/// A channel nobody declared answers false, so it goes to the broker: that is
/// the safe direction. A custom payload needs the addressee to be connected
/// here, and the broker does not.
#[must_use]
pub fn travels_as_payload(channel: &str) -> bool {
	CHANNELS
		.iter()
		.find(|spec| spec.channel == channel)
		.is_some_and(|spec| spec.transports.contains(&Transport::CustomPayload))
}

/// BungeeCord's channel, which is spelled two ways and means one thing.
const BUNGEE_MAIN: &str = "bungeecord:main";
const BUNGEE_ALIAS: &str = "bungeecord";

/// Normalise a channel name, or `None` when it is not one.
///
/// The pattern is the JVM's: a lowercase namespace and path either side of a
/// colon. Rejecting here rather than at the send is deliberate - a malformed
/// channel that reaches the broker is published to a queue nobody consumes, and
/// nothing about that failure says why.
#[must_use]
pub fn normalize(value: &str) -> Option<String> {
	let trimmed = value.trim();

	if trimmed.eq_ignore_ascii_case(BUNGEE_ALIAS) || trimmed.eq_ignore_ascii_case(BUNGEE_MAIN) {
		return Some(BUNGEE_MAIN.to_owned());
	}

	let lowered = trimmed.to_ascii_lowercase();
	let (namespace, path) = lowered.split_once(':')?;

	if namespace.is_empty() || path.is_empty() {
		return None;
	}

	let namespace_ok = namespace
		.bytes()
		.all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'));
	let path_ok = path
		.bytes()
		.all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'/'));

	if !namespace_ok || !path_ok {
		return None;
	}

	Some(lowered)
}

/// Whether the server owns this channel and a plugin may not take it over.
#[must_use]
pub fn is_reserved(channel: &str) -> bool {
	channel == "minecraft:register" || channel == "minecraft:unregister"
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn a_name_is_trimmed_and_lowercased() {
		assert_eq!(normalize("  Luna:Core_Player_Chat "), Some(CHAT_RELAY.to_owned()));
	}

	#[test]
	fn both_spellings_of_bungee_are_one_channel() {
		assert_eq!(normalize("BungeeCord"), Some(BUNGEE_MAIN.to_owned()));
		assert_eq!(normalize("bungeecord:main"), Some(BUNGEE_MAIN.to_owned()));
	}

	#[test]
	fn a_path_may_carry_slashes_and_a_namespace_may_not() {
		assert_eq!(normalize("luna:a/b"), Some("luna:a/b".to_owned()));
		assert_eq!(normalize("lu/na:b"), None);
	}

	#[test]
	fn something_that_is_not_a_channel_is_refused() {
		assert_eq!(normalize(""), None);
		assert_eq!(normalize("luna"), None);
		assert_eq!(normalize(":path"), None);
		assert_eq!(normalize("luna:"), None);
		assert_eq!(normalize("luna:has space"), None);
	}

	#[test]
	fn the_registry_channels_are_the_servers_own() {
		assert!(is_reserved("minecraft:register"));
		assert!(is_reserved("minecraft:unregister"));
		assert!(!is_reserved(CHAT_RELAY));
	}

	#[test]
	fn a_declared_channel_travels_on_the_connection() {
		assert!(travels_as_payload(CHAT_RELAY));
		assert!(travels_as_payload(SELECTOR_CONNECT));
	}

	/// TAB's bridge is not luna's channel and is not in the table; anything
	/// undeclared has to fall to the broker rather than be assumed local.
	#[test]
	fn an_undeclared_channel_does_not_claim_the_connection() {
		assert!(!travels_as_payload("tab:bridge-6"));
	}

	#[test]
	fn every_declared_channel_is_a_valid_name() {
		for spec in CHANNELS {
			assert_eq!(normalize(spec.channel).as_deref(), Some(spec.channel));
			assert!(!spec.transports.is_empty(), "{} carries nothing", spec.channel);
		}
	}
}
