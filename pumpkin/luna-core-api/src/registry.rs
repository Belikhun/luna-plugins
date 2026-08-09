//! The cluster's own view of every backend, as the proxy answers a heartbeat.
//!
//! The reply to a heartbeat is not an acknowledgement; it is the registry. Every
//! backend the proxy knows comes back in it as a `server.<n>.<field>` row, and
//! that is where a backend learns what the proxy calls *it*, what the others are
//! called, which are up, and how loaded they are. The server selector draws
//! itself entirely out of this, which is why it lives here rather than beside
//! the beat.
//!
//! Updates are **incremental by default**. The proxy sends `fullSync=false` and
//! only the rows that changed since the cursor it was given, so a store that
//! replaced its contents per reply would flicker down to one server and back.
//! Merging is the normal path; replacing happens only when the proxy says so.

use crate::heartbeat::decode_form;
use std::collections::BTreeMap;

/// One backend, as the proxy last heard from it.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BackendStatus {
	/// The proxy's name for it, which is what everything else keys on.
	pub name: String,
	pub display_name: String,
	pub accent_color: String,
	pub online: bool,
	/// Whether this row is the backend that asked.
	pub own: bool,
	pub last_heartbeat_millis: u64,
	pub software: String,
	pub version: String,
	pub port: u16,
	pub uptime_millis: u64,
	pub tps: f64,
	pub online_players: u32,
	pub max_players: u32,
	pub motd: String,
	/// A whitelisted backend is shown as under maintenance rather than open.
	pub whitelist_enabled: bool,
	pub cpu_percent: f64,
	pub ram_used_bytes: u64,
	pub ram_max_bytes: u64,
	pub latency_millis: u64,
}

impl BackendStatus {
	/// A name to show a player, falling back to the id when none was set.
	#[must_use]
	pub fn display(&self) -> &str {
		if self.display_name.trim().is_empty() {
			return &self.name;
		}

		&self.display_name
	}
}

/// What the proxy said about the backend that asked.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SelfIdentity {
	pub name: String,
	pub display_name: String,
	pub accent_color: String,
}

impl SelfIdentity {
	#[must_use]
	pub fn is_blank(&self) -> bool {
		self.name.trim().is_empty()
	}
}

/// One decoded heartbeat reply.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RegistryFrame {
	pub revision: u64,
	/// Whether these rows are the whole registry or only what changed.
	pub full_sync: bool,
	/// The proxy's row for this backend, taken from the `currentBackend*` fields.
	pub identity: SelfIdentity,
	pub servers: Vec<BackendStatus>,
}

/// Read a heartbeat reply.
///
/// Anything missing is a default rather than an error: the proxy has added
/// fields to this form over time and a backend that refused a reply it did not
/// fully recognise would drop off the network on the next proxy upgrade.
#[must_use]
pub fn decode_frame(body: &str) -> RegistryFrame {
	let fields = decode_form(body);
	let count = number(&fields, "serverCount").unwrap_or(0.0) as usize;

	let mut servers = Vec::with_capacity(count);

	for index in 0..count {
		let prefix = format!("server.{index}.");
		let name = text(&fields, &format!("{prefix}server_name"))
			.filter(|value| !value.trim().is_empty())
			.or_else(|| text(&fields, &format!("{prefix}name")))
			.unwrap_or_default();

		if name.trim().is_empty() {
			continue;
		}

		servers.push(BackendStatus {
			display_name: text(&fields, &format!("{prefix}server_display")).unwrap_or_default(),
			accent_color: text(&fields, &format!("{prefix}server_accent_color")).unwrap_or_default(),
			online: flag(&fields, &format!("{prefix}online")),
			own: flag(&fields, &format!("{prefix}self")),
			last_heartbeat_millis: integer(&fields, &format!("{prefix}lastHeartbeatEpochMillis")),
			software: text(&fields, &format!("{prefix}software")).unwrap_or_default(),
			version: text(&fields, &format!("{prefix}version")).unwrap_or_default(),
			port: integer(&fields, &format!("{prefix}serverPort")) as u16,
			uptime_millis: integer(&fields, &format!("{prefix}uptimeMillis")),
			tps: number(&fields, &format!("{prefix}tps")).unwrap_or(0.0),
			online_players: integer(&fields, &format!("{prefix}onlinePlayers")) as u32,
			max_players: integer(&fields, &format!("{prefix}maxPlayers")) as u32,
			motd: text(&fields, &format!("{prefix}motd")).unwrap_or_default(),
			whitelist_enabled: flag(&fields, &format!("{prefix}whitelistEnabled")),
			cpu_percent: number(&fields, &format!("{prefix}cpuUsagePercent")).unwrap_or(0.0),
			ram_used_bytes: integer(&fields, &format!("{prefix}ramUsedBytes")),
			ram_max_bytes: integer(&fields, &format!("{prefix}ramMaxBytes")),
			latency_millis: integer(&fields, &format!("{prefix}heartbeatLatencyMillis")),
			name,
		});
	}

	RegistryFrame {
		revision: integer(&fields, "revision"),
		full_sync: flag(&fields, "fullSync"),
		identity: SelfIdentity {
			name: text(&fields, "currentBackendName").unwrap_or_default(),
			display_name: text(&fields, "currentBackendDisplay").unwrap_or_default(),
			accent_color: text(&fields, "currentBackendAccentColor").unwrap_or_default(),
		},
		servers,
	}
}

/// Every backend the proxy has told us about, newest answer winning.
#[derive(Debug, Clone, Default)]
pub struct BackendRegistry {
	servers: BTreeMap<String, BackendStatus>,
}

impl BackendRegistry {
	#[must_use]
	pub fn new() -> Self {
		Self::default()
	}

	/// Fold one reply in. A full sync replaces; anything else merges.
	pub fn apply(&mut self, frame: &RegistryFrame) {
		if frame.full_sync {
			self.servers.clear();
		}

		for status in &frame.servers {
			self.servers.insert(status.name.clone(), status.clone());
		}
	}

	/// Every backend, ordered by name so a menu does not reshuffle itself.
	#[must_use]
	pub fn snapshot(&self) -> Vec<BackendStatus> {
		self.servers.values().cloned().collect()
	}

	#[must_use]
	pub fn get(&self, name: &str) -> Option<&BackendStatus> {
		self.servers.get(name)
	}

	#[must_use]
	pub fn len(&self) -> usize {
		self.servers.len()
	}

	#[must_use]
	pub fn is_empty(&self) -> bool {
		self.servers.is_empty()
	}
}

fn text(fields: &BTreeMap<String, String>, key: &str) -> Option<String> {
	fields.get(key).cloned()
}

fn flag(fields: &BTreeMap<String, String>, key: &str) -> bool {
	fields.get(key).is_some_and(|value| value == "true")
}

fn number(fields: &BTreeMap<String, String>, key: &str) -> Option<f64> {
	fields.get(key)?.trim().parse().ok()
}

fn integer(fields: &BTreeMap<String, String>, key: &str) -> u64 {
	// written as a float by some producers ("0.0"), so the parse goes through
	// f64 rather than refusing a value that is plainly a number
	number(fields, key).map_or(0, |value| value.max(0.0) as u64)
}

#[cfg(test)]
mod tests {
	use super::*;

	/// A real reply, copied from the dev proxy rather than invented.
	const REPLY: &str = "protocol=2&epoch=38591789-110a-4475-9c94-31cf607129dd&revision=436\
		&fullSync=false&serverCount=1&currentBackendName=pumpkintest\
		&currentBackendDisplay=pumpkintest&currentBackendAccentColor=\
		&currentBackendServerName=pumpkintest&server.0.server_name=pumpkintest\
		&server.0.server_display=pumpkintest&server.0.server_accent_color=\
		&server.0.name=pumpkintest&server.0.online=true\
		&server.0.lastHeartbeatEpochMillis=1786181209877&server.0.revision=436\
		&server.0.self=true&server.0.software=Pumpkin&server.0.version=test\
		&server.0.serverPort=32563&server.0.uptimeMillis=0&server.0.tps=20.0\
		&server.0.onlinePlayers=0&server.0.maxPlayers=100&server.0.motd=\
		&server.0.whitelistEnabled=false&server.0.systemCpuUsagePercent=0.0\
		&server.0.processCpuUsagePercent=0.0&server.0.cpuUsagePercent=0.0\
		&server.0.ramUsedBytes=0&server.0.ramFreeBytes=0&server.0.ramMaxBytes=0\
		&server.0.heartbeatLatencyMillis=0";

	#[test]
	fn a_real_reply_decodes() {
		let frame = decode_frame(REPLY);

		assert_eq!(frame.revision, 436);
		assert!(!frame.full_sync);
		assert_eq!(frame.servers.len(), 1);

		let server = &frame.servers[0];

		assert_eq!(server.name, "pumpkintest");
		assert!(server.online);
		assert!(server.own);
		assert_eq!(server.software, "Pumpkin");
		assert_eq!(server.port, 32563);
		assert_eq!(server.max_players, 100);
		assert!((server.tps - 20.0).abs() < f64::EPSILON);
		assert!(!server.whitelist_enabled);
	}

	/// The bug this module exists to end: the identity was read from
	/// `server_0_name`, which the proxy has never sent.
	#[test]
	fn the_identity_comes_from_the_current_backend_fields() {
		let frame = decode_frame(REPLY);

		assert_eq!(frame.identity.name, "pumpkintest");
		assert_eq!(frame.identity.display_name, "pumpkintest");
		assert!(!frame.identity.is_blank());
	}

	#[test]
	fn an_empty_body_is_an_empty_frame_rather_than_a_panic() {
		let frame = decode_frame("");

		assert!(frame.servers.is_empty());
		assert!(frame.identity.is_blank());
		assert_eq!(frame.revision, 0);
	}

	/// An incremental reply names only what changed, so folding it in must not
	/// take every other backend off the menu.
	#[test]
	fn an_incremental_frame_merges_rather_than_replaces() {
		let mut registry = BackendRegistry::new();

		registry.apply(&frame_with(&["lobby", "survival"], false));
		assert_eq!(registry.len(), 2);

		registry.apply(&frame_with(&["survival"], false));
		assert_eq!(registry.len(), 2);
		assert!(registry.get("lobby").is_some());
	}

	#[test]
	fn a_full_sync_replaces_the_whole_registry() {
		let mut registry = BackendRegistry::new();

		registry.apply(&frame_with(&["lobby", "survival"], false));
		registry.apply(&frame_with(&["survival"], true));

		assert_eq!(registry.len(), 1);
		assert!(registry.get("lobby").is_none());
	}

	#[test]
	fn a_row_with_no_name_is_skipped_rather_than_stored_blank() {
		let frame = decode_frame("serverCount=2&server.0.server_name=lobby&server.1.server_name=");

		assert_eq!(frame.servers.len(), 1);
		assert_eq!(frame.servers[0].name, "lobby");
	}

	#[test]
	fn a_display_name_falls_back_to_the_id() {
		let status = BackendStatus {
			name: "lobby".into(),
			display_name: "  ".into(),
			..BackendStatus::default()
		};

		assert_eq!(status.display(), "lobby");
	}

	#[test]
	fn the_snapshot_is_ordered_so_a_menu_does_not_reshuffle() {
		let mut registry = BackendRegistry::new();

		registry.apply(&frame_with(&["survival", "event", "lobby"], true));

		let names: Vec<String> = registry
			.snapshot()
			.into_iter()
			.map(|status| status.name)
			.collect();

		assert_eq!(names, vec!["event", "lobby", "survival"]);
	}

	fn frame_with(names: &[&str], full_sync: bool) -> RegistryFrame {
		RegistryFrame {
			revision: 1,
			full_sync,
			identity: SelfIdentity::default(),
			servers: names
				.iter()
				.map(|name| BackendStatus {
					name: (*name).to_owned(),
					online: true,
					..BackendStatus::default()
				})
				.collect(),
		}
	}
}
