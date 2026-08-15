//! The heartbeat a backend publishes to the proxy, and how it is encoded.
//!
//! The proxy reads an ordinary `application/x-www-form-urlencoded` body, so this
//! carries no framing of its own; what it does have to match is the **field
//! names**, which `HeartbeatFormCodec` on the JVM side fixes.

use std::collections::BTreeMap;

/// The facts a backend reports about itself on every beat.
///
/// `heartbeat_latency_millis` is the proxy's to fill in, not ours; it is written
/// as zero exactly as every other platform writes it.
#[derive(Debug, Clone, PartialEq)]
pub struct HeartbeatStats {
	pub software: String,
	pub version: String,
	pub server_port: i32,
	pub uptime_millis: i64,
	pub tps: f64,
	pub online_players: i32,
	pub max_players: i32,
	pub motd: String,
	pub whitelist_enabled: bool,
	pub system_cpu_usage_percent: f64,
	pub process_cpu_usage_percent: f64,
	pub ram_used_bytes: i64,
	pub ram_free_bytes: i64,
	pub ram_max_bytes: i64,
	/// One row per world, in whatever order the server lists them.
	pub worlds: Vec<WorldStats>,
}

/// What one world is holding.
///
/// The JVM platforms split entities by whether the chunk they stand in is being
/// ticked. Pumpkin has no unsimulated-but-loaded state to describe yet, so
/// everything it counts goes in `ticking_entities`, which is true here for the
/// same reason it is true on 1.12.
///
/// `-1` means the sandbox could not measure that counter, which is a different
/// statement from zero: the plugin API lists a world's entities but has no way
/// to ask how many chunks are loaded, so that one is always unmeasured.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorldStats {
	pub name: String,
	pub loaded_chunks: i32,
	pub ticking_entities: i32,
	pub non_ticking_entities: i32,
}

/// A counter this platform could not measure.
pub const UNKNOWN_COUNT: i32 = -1;

impl HeartbeatStats {
	/// The field set the proxy expects, in the order the JVM codec writes it.
	#[must_use]
	pub fn to_fields(&self) -> Vec<(String, String)> {
		let mut fields: Vec<(String, String)> = vec![
			("software".into(), self.software.clone()),
			("version".into(), self.version.clone()),
			("serverPort".into(), self.server_port.to_string()),
			("uptimeMillis".into(), self.uptime_millis.to_string()),
			("tps".into(), format_java_double(self.tps)),
			("onlinePlayers".into(), self.online_players.to_string()),
			("maxPlayers".into(), self.max_players.to_string()),
			("motd".into(), self.motd.clone()),
			(
				"whitelistEnabled".into(),
				self.whitelist_enabled.to_string(),
			),
			(
				"systemCpuUsagePercent".into(),
				format_java_double(self.system_cpu_usage_percent),
			),
			(
				"processCpuUsagePercent".into(),
				format_java_double(self.process_cpu_usage_percent),
			),
			// the proxy still reads the older singular name, and the JVM codec
			// keeps writing both; dropping it here would be a silent regression
			(
				"cpuUsagePercent".into(),
				format_java_double(self.system_cpu_usage_percent),
			),
			("ramUsedBytes".into(), self.ram_used_bytes.to_string()),
			("ramFreeBytes".into(), self.ram_free_bytes.to_string()),
			("ramMaxBytes".into(), self.ram_max_bytes.to_string()),
			("heartbeatLatencyMillis".into(), "0".into()),
		];

		// indexed keys inside the row, the same shape the JVM codec writes; the
		// count goes on afterwards so it can never name more worlds than were
		// actually encoded. No tick fields at all: this sandbox is given no tick
		// event to time, and the proxy reads their absence as "not measured".
		let mut encoded = 0;

		for world in &self.worlds {
			if world.name.trim().is_empty() {
				continue;
			}

			let prefix = format!("world.{encoded}.");
			fields.push((format!("{prefix}name"), world.name.clone()));
			fields.push((format!("{prefix}chunks"), world.loaded_chunks.to_string()));
			fields.push((format!("{prefix}ticking"), world.ticking_entities.to_string()));
			fields.push((
				format!("{prefix}nonTicking"),
				world.non_ticking_entities.to_string(),
			));
			encoded += 1;
		}

		if encoded > 0 {
			fields.push(("worldCount".into(), encoded.to_string()));
		}

		fields
	}
}

/// Render `20.0` rather than `20`, the way `String.valueOf(double)` does.
///
/// The proxy parses these with `Double.parseDouble`, which accepts both, but a
/// log line comparing two platforms should not read as though they disagree.
fn format_java_double(value: f64) -> String {
	if !value.is_finite() {
		return "0.0".into();
	}

	if value.fract() == 0.0 && value.abs() < 1e16 {
		return format!("{value:.1}");
	}

	value.to_string()
}

/// Encode form fields the way the proxy's decoder expects to read them.
#[must_use]
pub fn encode_form(fields: &[(String, String)]) -> String {
	let mut out = String::new();

	for (key, value) in fields {
		if key.trim().is_empty() {
			continue;
		}

		if !out.is_empty() {
			out.push('&');
		}

		out.push_str(&percent_encode(key));
		out.push('=');
		out.push_str(&percent_encode(value));
	}

	out
}

/// Decode a form body into its pairs, keeping the last value for a repeated key.
#[must_use]
pub fn decode_form(body: &str) -> BTreeMap<String, String> {
	let mut out = BTreeMap::new();

	for pair in body.split('&') {
		if pair.is_empty() {
			continue;
		}

		let (key, value) = match pair.split_once('=') {
			Some(split) => split,
			None => (pair, ""),
		};

		out.insert(percent_decode(key), percent_decode(value));
	}

	out
}

fn percent_encode(value: &str) -> String {
	let mut out = String::with_capacity(value.len());

	for byte in value.as_bytes() {
		let ch = *byte;
		let unreserved = ch.is_ascii_alphanumeric() || matches!(ch, b'-' | b'_' | b'.' | b'~');

		if unreserved {
			out.push(ch as char);
		} else {
			out.push('%');
			out.push_str(&format!("{ch:02X}"));
		}
	}

	out
}

fn percent_decode(value: &str) -> String {
	let bytes = value.as_bytes();
	let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
	let mut index = 0;

	while index < bytes.len() {
		match bytes[index] {
			b'+' => {
				out.push(b' ');
				index += 1;
			}
			b'%' if index + 2 < bytes.len() => {
				let hex = std::str::from_utf8(&bytes[index + 1..index + 3]).unwrap_or("");
				match u8::from_str_radix(hex, 16) {
					Ok(decoded) => {
						out.push(decoded);
						index += 3;
					}
					Err(_) => {
						out.push(b'%');
						index += 1;
					}
				}
			}
			other => {
				out.push(other);
				index += 1;
			}
		}
	}

	String::from_utf8_lossy(&out).into_owned()
}

/// The tick rate the cluster means by "TPS".
const TARGET_TPS: f64 = 20.0;

/// Normalise a platform's tick figure into the one every backend reports.
///
/// The JVM platforms report the rate the server actually ticks at, capped at the
/// target, and that is what the console charts and the server selector compare
/// across backends. Not every platform means the same thing by "TPS": Pumpkin's
/// is `1000 / mspt`, where mspt is how long a tick took to *execute*, so an idle
/// server reads about 16000 - headroom, not a rate. Clamping gives the shared
/// meaning, and a server falling behind still reports its real rate, because a
/// tick costing more than 50ms drops the figure below the cap on its own.
#[must_use]
pub fn effective_tps(reported: f64) -> f64 {
	if !reported.is_finite() || reported <= 0.0 {
		return 0.0;
	}

	reported.min(TARGET_TPS)
}

/// The endpoint a backend posts its heartbeat to, with its name on the end.
#[must_use]
pub fn heartbeat_url(endpoint: &str, server_name: &str) -> String {
	format!(
		"{}/{}",
		endpoint.trim_end_matches('/'),
		encode_path_segment(server_name)
	)
}

/// Where the proxy serves a backend's broker settings.
///
/// Derived from the heartbeat endpoint rather than configured separately, so a
/// backend only ever carries one address; this is the sibling rule
/// `BackendRegistryClient` applies on the JVM platforms.
#[must_use]
pub fn messaging_config_url(endpoint: &str, server_name: &str) -> String {
	let base = endpoint.trim_end_matches('/');
	let sibling = match base.rfind('/') {
		Some(index) => format!("{}/messaging-config", &base[..index]),
		None => format!("{base}/messaging-config"),
	};

	format!("{sibling}/{}", encode_path_segment(server_name))
}

/// Where the proxy serves the server selector's layout.
///
/// A sibling of the heartbeat endpoint like the messaging config, but with no
/// name segment: the menu is the cluster's, not one backend's.
#[must_use]
pub fn selector_config_url(endpoint: &str) -> String {
	let base = endpoint.trim_end_matches('/');

	match base.rfind('/') {
		Some(index) => format!("{}/server-selector-config", &base[..index]),
		None => format!("{base}/server-selector-config"),
	}
}

/// Percent-encode a name for use as one path segment.
fn encode_path_segment(value: &str) -> String {
	let mut out = String::with_capacity(value.len());

	for byte in value.as_bytes() {
		let ch = *byte;

		if ch.is_ascii_alphanumeric() || matches!(ch, b'-' | b'_' | b'.') {
			out.push(ch as char);
		} else {
			out.push('%');
			out.push_str(&format!("{ch:02X}"));
		}
	}

	out
}

#[cfg(test)]
mod tests {
	use super::*;

	fn sample() -> HeartbeatStats {
		HeartbeatStats {
			software: "Pumpkin".into(),
			version: "1.21.4".into(),
			server_port: 25565,
			uptime_millis: 1234,
			tps: 20.0,
			online_players: 3,
			max_players: 64,
			motd: "A luna backend".into(),
			whitelist_enabled: false,
			system_cpu_usage_percent: 12.5,
			process_cpu_usage_percent: 4.0,
			ram_used_bytes: 100,
			ram_free_bytes: 200,
			ram_max_bytes: 300,
		}
	}

	#[test]
	fn writes_java_style_doubles() {
		assert_eq!(format_java_double(20.0), "20.0");
		assert_eq!(format_java_double(12.5), "12.5");
		assert_eq!(format_java_double(f64::NAN), "0.0");
	}

	#[test]
	fn carries_both_cpu_field_names() {
		let fields = sample().to_fields();
		let keys: Vec<&str> = fields.iter().map(|(k, _)| k.as_str()).collect();

		assert!(keys.contains(&"systemCpuUsagePercent"));
		assert!(keys.contains(&"cpuUsagePercent"));
	}

	#[test]
	fn round_trips_through_the_form() {
		let fields = sample().to_fields();
		let decoded = decode_form(&encode_form(&fields));

		assert_eq!(decoded.get("software").map(String::as_str), Some("Pumpkin"));
		assert_eq!(decoded.get("motd").map(String::as_str), Some("A luna backend"));
		assert_eq!(decoded.get("tps").map(String::as_str), Some("20.0"));
	}

	#[test]
	fn escapes_what_would_break_the_body() {
		let encoded = encode_form(&[("motd".into(), "a&b=c d".into())]);

		assert_eq!(encoded, "motd=a%26b%3Dc%20d");
		assert_eq!(
			decode_form(&encoded).get("motd").map(String::as_str),
			Some("a&b=c d")
		);
	}

	#[test]
	fn decodes_a_plus_as_a_space() {
		assert_eq!(
			decode_form("motd=a+b").get("motd").map(String::as_str),
			Some("a b")
		);
	}

	#[test]
	fn tps_is_capped_at_the_rate_the_cluster_compares() {
		// pumpkin's idle figure is throughput, not a rate
		assert_eq!(effective_tps(15898.25), 20.0);
		assert_eq!(effective_tps(20.0), 20.0);
	}

	#[test]
	fn a_server_falling_behind_reports_its_real_rate() {
		assert_eq!(effective_tps(12.5), 12.5);
	}

	#[test]
	fn a_nonsense_tps_reads_as_zero_rather_than_propagating() {
		assert_eq!(effective_tps(0.0), 0.0);
		assert_eq!(effective_tps(-1.0), 0.0);
		assert_eq!(effective_tps(f64::NAN), 0.0);
		assert_eq!(effective_tps(f64::INFINITY), 0.0);
	}

	#[test]
	fn appends_the_name_as_a_path_segment() {
		assert_eq!(
			heartbeat_url("http://127.0.0.1:32452/api/heartbeat", "lobby"),
			"http://127.0.0.1:32452/api/heartbeat/lobby"
		);
	}

	#[test]
	fn escapes_a_name_that_is_not_url_safe() {
		assert_eq!(
			heartbeat_url("http://127.0.0.1:32452/api/heartbeat", "127.0.0.1:25565"),
			"http://127.0.0.1:32452/api/heartbeat/127.0.0.1%3A25565"
		);
	}

	#[test]
	fn a_trailing_slash_does_not_double_up() {
		assert_eq!(
			heartbeat_url("http://127.0.0.1:32452/api/heartbeat/", "lobby"),
			"http://127.0.0.1:32452/api/heartbeat/lobby"
		);
	}

	#[test]
	fn derives_the_messaging_endpoint_as_a_sibling() {
		assert_eq!(
			messaging_config_url("http://127.0.0.1:32452/api/heartbeat", "lobby"),
			"http://127.0.0.1:32452/api/messaging-config/lobby"
		);
	}
}
