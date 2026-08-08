//! The backend's own settings, read from its private data folder.
//!
//! TOML rather than the `config.yml` the JVM platforms use, because Pumpkin's
//! own configuration is TOML and `control`'s `confedit` already speaks it, so a
//! managed config file needs no new machinery on either side.
//!
//! Two values are here that no other platform needs in its file. The sandbox
//! preopens only `plugins/data/<name>`, so this plugin cannot read Pumpkin's
//! `configuration.toml`; the **forwarding secret** and the **server port** have
//! to be handed to it rather than discovered. luna owns provisioning, so
//! `control` writes them when it creates the instance.

use luna_core_api::heartbeat;
use serde::{Deserialize, Serialize};

/// Everything the core reads at startup.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CoreConfig {
	pub heartbeat: HeartbeatConfig,
	pub logging: LoggingConfig,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct HeartbeatConfig {
	pub enabled: bool,
	/// The proxy's heartbeat endpoint; this backend's name is appended to it.
	pub endpoint: String,
	/// What to call this backend; blank lets the proxy decide.
	pub server_name: String,
	/// The velocity forwarding secret, which authenticates every request.
	pub forwarding_secret: String,
	/// This server's own port. The plugin API does not expose it.
	pub server_port: u16,
	pub interval_seconds: u64,
	pub connect_timeout_millis: u64,
	pub read_timeout_millis: u64,
	pub transport_logging_enabled: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct LoggingConfig {
	pub level: String,
	pub plugin_messaging_enabled: bool,
}

impl Default for CoreConfig {
	fn default() -> Self {
		Self {
			heartbeat: HeartbeatConfig::default(),
			logging: LoggingConfig::default(),
		}
	}
}

impl Default for HeartbeatConfig {
	fn default() -> Self {
		Self {
			enabled: true,
			endpoint: "http://127.0.0.1:32452/api/heartbeat".into(),
			server_name: String::new(),
			forwarding_secret: String::new(),
			server_port: 25565,
			interval_seconds: 5,
			connect_timeout_millis: 3000,
			read_timeout_millis: 3000,
			transport_logging_enabled: false,
		}
	}
}

impl Default for LoggingConfig {
	fn default() -> Self {
		Self {
			level: "INFO".into(),
			plugin_messaging_enabled: false,
		}
	}
}

impl CoreConfig {
	/// Read the config, writing the defaults out when the file is not there yet.
	///
	/// A malformed file is not fatal: the backend still heartbeats on defaults,
	/// because a server invisible to the console is a worse failure than one
	/// running with settings the operator has to correct. The caller logs it.
	pub fn load_or_create(data_folder: &str) -> (Self, Option<String>) {
		let path = std::path::Path::new(data_folder).join("config.toml");

		match std::fs::read_to_string(&path) {
			Ok(body) => match toml::from_str::<Self>(&body) {
				Ok(config) => (config, None),
				Err(error) => (
					Self::default(),
					Some(format!("config tại {} không hợp lệ: {error}", path.display())),
				),
			},
			Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
				let config = Self::default();
				let note = match Self::write(&path, &config) {
					Ok(()) => format!("Đã tạo config mặc định tại {}.", path.display()),
					Err(write_error) => {
						format!("Không thể ghi config mặc định: {write_error}")
					}
				};

				(config, Some(note))
			}
			Err(error) => (
				Self::default(),
				Some(format!("Không thể đọc config: {error}")),
			),
		}
	}

	fn write(path: &std::path::Path, config: &Self) -> std::io::Result<()> {
		let body = toml::to_string_pretty(config)
			.map_err(|error| std::io::Error::other(error.to_string()))?;

		if let Some(parent) = path.parent() {
			std::fs::create_dir_all(parent)?;
		}

		std::fs::write(path, body)
	}

	/// The endpoint this backend posts its heartbeat to.
	#[must_use]
	pub fn heartbeat_url(&self, server_name: &str) -> String {
		heartbeat::heartbeat_url(&self.heartbeat.endpoint, server_name)
	}

	/// Where the proxy serves this backend's broker settings.
	#[must_use]
	pub fn messaging_config_url(&self, server_name: &str) -> String {
		heartbeat::messaging_config_url(&self.heartbeat.endpoint, server_name)
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn defaults_are_usable_without_a_file() {
		let config = CoreConfig::default();

		assert!(config.heartbeat.enabled);
		assert_eq!(config.heartbeat.interval_seconds, 5);
	}

	#[test]
	fn round_trips_through_toml() {
		let config = CoreConfig::default();
		let parsed: CoreConfig = toml::from_str(&toml::to_string(&config).expect("encodes"))
			.expect("decodes");

		assert_eq!(parsed.heartbeat.endpoint, config.heartbeat.endpoint);
		assert_eq!(parsed.logging.level, config.logging.level);
	}

	#[test]
	fn a_partial_file_keeps_the_defaults_for_what_it_omits() {
		let parsed: CoreConfig = toml::from_str("[heartbeat]\nserverName = \"lobby\"\n")
			.expect("decodes");

		assert_eq!(parsed.heartbeat.server_name, "lobby");
		assert_eq!(parsed.heartbeat.interval_seconds, 5);
		assert!(parsed.heartbeat.enabled);
	}
}
