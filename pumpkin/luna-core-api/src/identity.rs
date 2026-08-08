//! What this backend calls itself on the network.
//!
//! The same rule every other platform follows since the loaders were aligned
//! with Paper: the proxy's own row wins, the configured name is the fallback,
//! and `host:port` is the last resort. Read it per use - at boot none of the
//! three are known yet, and a name captured then is the bug that had an
//! unconfigured backend binding `luna.backend.backend`.

use std::sync::{Arc, RwLock};

/// The proxy's view of this backend, as far as it has told us.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BackendMetadata {
	pub name: String,
	pub display_name: String,
	pub accent_color: String,
}

impl BackendMetadata {
	#[must_use]
	pub fn is_blank(&self) -> bool {
		self.name.trim().is_empty()
	}
}

/// A backend's identity, shared and updated as the proxy answers.
#[derive(Debug, Clone)]
pub struct BackendIdentity {
	from_proxy: Arc<RwLock<Option<BackendMetadata>>>,
	configured_name: String,
	fallback_name: String,
}

impl BackendIdentity {
	/// `configured_name` is the operator's `heartbeat.serverName`, possibly empty.
	#[must_use]
	pub fn new(configured_name: impl Into<String>, port: u16) -> Self {
		Self {
			from_proxy: Arc::new(RwLock::new(None)),
			configured_name: configured_name.into().trim().to_owned(),
			fallback_name: format!("127.0.0.1:{}", if port == 0 { 25565 } else { port }),
		}
	}

	/// Record what the proxy called us; a blank row is ignored rather than stored.
	pub fn observe(&self, metadata: BackendMetadata) {
		if metadata.is_blank() {
			return;
		}

		if let Ok(mut slot) = self.from_proxy.write() {
			*slot = Some(metadata);
		}
	}

	/// The name to use right now.
	#[must_use]
	pub fn name(&self) -> String {
		if let Ok(slot) = self.from_proxy.read() {
			if let Some(metadata) = slot.as_ref() {
				if !metadata.is_blank() {
					return metadata.name.clone();
				}
			}
		}

		if !self.configured_name.is_empty() {
			return self.configured_name.clone();
		}

		self.fallback_name.clone()
	}

	/// The name as a queue name: lowercase, and anything exotic folded to `-`.
	///
	/// This has to agree with `AmqpMessagingConfig.normalizeServerName` on the
	/// JVM side or a Pumpkin backend would consume from a queue the proxy never
	/// publishes to.
	#[must_use]
	pub fn normalized_name(&self) -> String {
		normalize_server_name(&self.name())
	}
}

/// Fold a server name into the character set a queue name may use.
#[must_use]
pub fn normalize_server_name(value: &str) -> String {
	value
		.trim()
		.to_lowercase()
		.chars()
		.map(|ch| {
			if ch.is_ascii_lowercase() || ch.is_ascii_digit() || matches!(ch, '-' | '_' | '.') {
				ch
			} else {
				'-'
			}
		})
		.collect()
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn falls_back_to_host_and_port_when_nothing_is_known() {
		let identity = BackendIdentity::new("", 32561);

		assert_eq!(identity.name(), "127.0.0.1:32561");
	}

	#[test]
	fn prefers_the_configured_name_over_the_fallback() {
		let identity = BackendIdentity::new("pumpkin1", 32561);

		assert_eq!(identity.name(), "pumpkin1");
	}

	#[test]
	fn the_proxy_wins_once_it_answers() {
		let identity = BackendIdentity::new("pumpkin1", 32561);
		identity.observe(BackendMetadata {
			name: "lobby".into(),
			display_name: "Lobby".into(),
			accent_color: String::new(),
		});

		assert_eq!(identity.name(), "lobby");
	}

	#[test]
	fn a_blank_row_is_ignored() {
		let identity = BackendIdentity::new("pumpkin1", 32561);
		identity.observe(BackendMetadata::default());

		assert_eq!(identity.name(), "pumpkin1");
	}

	#[test]
	fn normalizes_the_way_the_jvm_does() {
		assert_eq!(normalize_server_name("Lobby"), "lobby");
		assert_eq!(normalize_server_name("127.0.0.1:25565"), "127.0.0.1-25565");
		assert_eq!(normalize_server_name(" Event 2 "), "event-2");
	}
}
