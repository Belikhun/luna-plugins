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
use std::collections::BTreeMap;

/// Everything the core reads at startup.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CoreConfig {
	pub heartbeat: HeartbeatConfig,
	pub logging: LoggingConfig,
	pub auth: AuthConfig,
}

/// What the auth backend shows and what it lets through.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AuthConfig {
	/// Off for a backend the proxy does not gate, so nothing is ever locked.
	pub enabled: bool,
	/// Whether every refusal and state change is written to the log.
	pub log_flow: bool,
	pub mode_selector_enabled: bool,
	/// Empty a player's inventory when the lock goes on, as the Paper plugin
	/// does. It is on by default because that is Paper's behaviour and the
	/// backends this gates are lobbies, where nothing is carried; on a backend
	/// where players keep an inventory, turn it off, because the items are not
	/// given back - Paper only restores lobby items, which this port has no
	/// registry for.
	pub clear_inventory_on_lock: bool,
	/// What an unauthenticated player may still type.
	pub allowed_commands: Vec<String>,
	pub prompt: PromptConfig,
}

/// The three prompts, the congratulation, and its per-method variants.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PromptConfig {
	pub pending: PromptStrings,
	pub login: PromptStrings,
	pub register: PromptStrings,
	pub authenticated: PromptStrings,
	/// Keyed by the normalised auth method; see `luna_core_api::auth`.
	pub by_method: BTreeMap<String, PromptStrings>,
}

/// One prompt, in the three places it is shown.
///
/// These are MiniMessage, the same as every JVM backend's, so a config can be
/// copied across from a Paper server unchanged; `luna_core_api::text` is what
/// reads them, since Pumpkin's text component does not.
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PromptStrings {
	pub bossbar: String,
	pub actionbar: String,
	pub chat: String,
}

impl PromptStrings {
	fn of(bossbar: &str, actionbar: &str, chat: &str) -> Self {
		Self {
			bossbar: bossbar.to_owned(),
			actionbar: actionbar.to_owned(),
			chat: chat.to_owned(),
		}
	}
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
			auth: AuthConfig::default(),
		}
	}
}

impl Default for AuthConfig {
	fn default() -> Self {
		Self {
			enabled: true,
			log_flow: true,
			mode_selector_enabled: true,
			clear_inventory_on_lock: true,
			allowed_commands: ["login", "register", "l", "reg", "help"]
				.iter()
				.map(|command| (*command).to_owned())
				.collect(),
			prompt: PromptConfig::default(),
		}
	}
}

impl Default for PromptConfig {
	fn default() -> Self {
		// These are `luna-auth-backend/src/main/resources/config.yml` verbatim.
		// An operator running one Paper backend and one Pumpkin backend should
		// see the same words on both, so the defaults are copied rather than
		// rewritten to suit this platform.
		let mut by_method = BTreeMap::new();

		by_method.insert(
			"quick_login".to_owned(),
			PromptStrings::of(
				"",
				"<green>✔ <color:#00c2ff><b>Luna QuikAuth™</b></color> xác thực tức thì</green>",
				"<green>✔ Bạn đã được <color:#00c2ff><b>Luna QuikAuth™</b></color> xác thực bằng phiên Premium hợp lệ.</green>",
			),
		);
		by_method.insert(
			"session_resume".to_owned(),
			PromptStrings::of(
				"",
				"<green>✔ Phiên đăng nhập đã khôi phục</green>",
				"<green>✔ Phiên trước vẫn còn hiệu lực, không cần nhập lại mật khẩu.</green>",
			),
		);
		by_method.insert(
			"password_login".to_owned(),
			PromptStrings::of(
				"",
				"<green>✔ Đăng nhập mật khẩu thành công</green>",
				"<green>✔ Bạn đã xác thực bằng mật khẩu thành công.</green>",
			),
		);
		by_method.insert(
			"register_password".to_owned(),
			PromptStrings::of(
				"",
				"<green>✔ Tạo tài khoản thành công</green>",
				"<green>✔ Tài khoản mới đã được tạo và xác thực.</green>",
			),
		);

		Self {
			pending: PromptStrings::of(
				"<yellow><b>⏳ Đang tải trạng thái xác thực...</b></yellow>",
				"<yellow>Đang kiểm tra trạng thái tài khoản...</yellow>",
				"<yellow>ℹ Đang kiểm tra trạng thái xác thực, vui lòng chờ một chút.</yellow>",
			),
			login: PromptStrings::of(
				"<yellow><b>⚠ Vui lòng đăng nhập để tiếp tục</b></yellow>",
				"<yellow>Dùng <white>/login <mật_khẩu></white> để đăng nhập</yellow>",
				"<yellow>ℹ Tài khoản đã đăng ký. Dùng <white>/login <mật_khẩu></white> để tiếp tục.</yellow>",
			),
			register: PromptStrings::of(
				"<yellow><b>⚠ Tài khoản chưa đăng ký</b></yellow>",
				"<yellow>Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tạo tài khoản</yellow>",
				"<yellow>ℹ Tài khoản chưa đăng ký. Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tiếp tục.</yellow>",
			),
			authenticated: PromptStrings::of(
				"",
				"<green>✔ Đã xác thực thành công</green>",
				"<green>✔ Bạn đã xác thực thành công. Chúc bạn chơi vui vẻ!</green>",
			),
			by_method,
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
