//! The broker settings the proxy pushes to every backend.
//!
//! This is the Rust counterpart of `AmqpMessagingConfig` on the JVM side, and
//! like it, a backend never reads these from its own file: the proxy owns them
//! and serves them, so moving the cluster to another broker or turning messaging
//! off centrally reaches every backend without touching any of them.
//!
//! Nothing here talks to a broker. Which client drives the socket is a platform
//! question; what the settings mean is not, so this is shared and tested.

use std::collections::BTreeMap;

/// What the proxy told this backend about the broker.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AmqpConfig {
	pub enabled: bool,
	pub uri: String,
	pub exchange: String,
	pub proxy_queue: String,
	pub backend_queue_prefix: String,
}

impl AmqpConfig {
	/// Read the settings out of a decoded `messaging-config` body.
	///
	/// The field names are the proxy's, so they match `AmqpMessagingConfigCodec`
	/// on the JVM side exactly; anything missing leaves its field empty, which
	/// [`is_configured`](Self::is_configured) then refuses.
	#[must_use]
	pub fn from_fields(fields: &BTreeMap<String, String>) -> Self {
		let value = |key: &str| fields.get(key).cloned().unwrap_or_default();

		Self {
			enabled: value("enabled") == "true",
			uri: value("uri"),
			exchange: value("exchange"),
			proxy_queue: value("proxyQueue"),
			backend_queue_prefix: value("backendQueuePrefix"),
		}
	}

	/// Whether there is enough here to attempt a connection.
	#[must_use]
	pub fn is_configured(&self) -> bool {
		self.enabled
			&& !self.uri.trim().is_empty()
			&& !self.exchange.trim().is_empty()
			&& !self.proxy_queue.trim().is_empty()
			&& !self.backend_queue_prefix.trim().is_empty()
	}

	/// This backend's own queue name.
	#[must_use]
	pub fn backend_queue(&self, normalized_server_name: &str) -> String {
		format!("{}{normalized_server_name}", self.backend_queue_prefix)
	}

	/// The URI with its credentials masked, for logging.
	///
	/// A broker URI carries a password, and this is the only form of it that may
	/// reach a log file or the console.
	#[must_use]
	pub fn masked_uri(&self) -> String {
		let Some(scheme_end) = self.uri.find("://") else {
			return self.uri.clone();
		};
		let Some(at) = self.uri.find('@') else {
			return self.uri.clone();
		};

		if at <= scheme_end + 3 {
			return self.uri.clone();
		}

		let credential_start = scheme_end + 3;
		match self.uri[credential_start..at].find(':') {
			Some(offset) => {
				let colon = credential_start + offset;
				format!("{}***@{}", &self.uri[..=colon], &self.uri[at + 1..])
			}
			None => format!("{}***@{}", &self.uri[..credential_start], &self.uri[at + 1..]),
		}
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn configured() -> AmqpConfig {
		AmqpConfig {
			enabled: true,
			uri: "amqp://guest:guest@127.0.0.1:5672/%2F".into(),
			exchange: "luna.plugin-messaging".into(),
			proxy_queue: "luna.proxy.messaging".into(),
			backend_queue_prefix: "luna.backend.".into(),
		}
	}

	#[test]
	fn a_disabled_config_is_not_configured() {
		let mut config = configured();
		config.enabled = false;

		assert!(!config.is_configured());
	}

	#[test]
	fn a_config_missing_a_field_is_not_configured() {
		let mut config = configured();
		config.exchange = String::new();

		assert!(!config.is_configured());
	}

	#[test]
	fn builds_the_queue_name_the_proxy_publishes_to() {
		assert_eq!(configured().backend_queue("lobby"), "luna.backend.lobby");
	}

	#[test]
	fn masks_credentials_for_logging() {
		assert_eq!(
			configured().masked_uri(),
			"amqp://guest:***@127.0.0.1:5672/%2F"
		);
	}

	#[test]
	fn leaves_a_uri_without_credentials_alone() {
		let mut config = configured();
		config.uri = "amqp://127.0.0.1:5672/%2F".into();

		assert_eq!(config.masked_uri(), "amqp://127.0.0.1:5672/%2F");
	}

	#[test]
	fn reads_the_body_the_proxy_serves() {
		// exactly what /api/messaging-config/<name> returns, once form-decoded
		let fields = BTreeMap::from([
			("enabled".to_owned(), "true".to_owned()),
			("uri".to_owned(), "amqp://guest:guest@broker:5672/%2F".to_owned()),
			("exchange".to_owned(), "luna.plugin-messaging".to_owned()),
			("proxyQueue".to_owned(), "luna.proxy.messaging".to_owned()),
			("backendQueuePrefix".to_owned(), "luna.backend.".to_owned()),
		]);

		let config = AmqpConfig::from_fields(&fields);

		assert!(config.is_configured());
		assert_eq!(config.backend_queue("lobby"), "luna.backend.lobby");
	}

	#[test]
	fn a_body_that_disables_messaging_is_refused_rather_than_half_read() {
		let fields = BTreeMap::from([("enabled".to_owned(), "false".to_owned())]);

		assert!(!AmqpConfig::from_fields(&fields).is_configured());
	}
}
