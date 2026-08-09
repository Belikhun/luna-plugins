//! The core's runtime state and the beat itself.
//!
//! One tick does the whole exchange: collect what the server says about itself,
//! post it, and take the reply's view of who this backend is. That last part is
//! why the identity is shared rather than computed once - the proxy's answer is
//! what names the AMQP queue and the `current_server` placeholder later.

use crate::amqp::AmqpTransport;
use std::sync::Arc;
use crate::config::CoreConfig;
use crate::http;
use luna_core_api::heartbeat::{
	HeartbeatStats, decode_form, effective_tps, encode_form, selector_config_url,
};

use luna_core_api::host_metrics::{HostMetrics, decode_host_metrics};
use luna_core_api::identity::{BackendIdentity, BackendMetadata};
use luna_core_api::registry::{BackendRegistry, decode_frame};
use luna_core_api::messaging::AmqpConfig;
use pumpkin_plugin_api::Server;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// What the daemon leaves in the plugin's data folder, and the only file in
/// there this component did not write itself.
const HOST_METRICS_FILE: &str = "host-metrics";

/// Everything the beat needs, shared with the scheduled task.
pub struct CoreState {
	config: CoreConfig,
	identity: BackendIdentity,
	/// Shared with the message bus: the broker is messaging's, and the beat only
	/// borrows it to hand over the settings the proxy publishes.
	transport: Arc<AmqpTransport>,
	boot_epoch_millis: u64,
	/// Where the registry cursor is; the proxy sends what changed after it.
	cursor: AtomicU64,
	/// How many beats in a row failed, so the log says it once rather than each time.
	consecutive_failures: AtomicU64,
	/// The last messaging-config body seen, so an unchanged one is not re-applied.
	messaging_body: Mutex<Option<String>>,
	/// The last selector layout seen, for the same reason.
	selector_body: Mutex<Option<Vec<u8>>>,
	/// Every backend the proxy has told us about, which is what the selector draws.
	registry: Arc<Mutex<BackendRegistry>>,
	/// The plugin's own data folder, where the daemon leaves this instance's
	/// CPU and memory; see [`luna_core_api::host_metrics`].
	data_folder: String,
	/// Whether the last read found a fresh sample, so the change is logged once.
	host_metrics_seen: AtomicBool,
}

impl CoreState {
	#[must_use]
	pub fn new(
		config: CoreConfig,
		identity: BackendIdentity,
		transport: Arc<AmqpTransport>,
		data_folder: String,
	) -> Self {
		Self {
			config,
			identity,
			transport,
			boot_epoch_millis: now_millis(),
			cursor: AtomicU64::new(0),
			consecutive_failures: AtomicU64::new(0),
			messaging_body: Mutex::new(None),
			selector_body: Mutex::new(None),
			registry: Arc::new(Mutex::new(BackendRegistry::new())),
			data_folder,
			host_metrics_seen: AtomicBool::new(false),
		}
	}

	/// What the daemon last measured about this instance, if it is still current.
	///
	/// Read per beat rather than cached: the file is a few dozen bytes in the
	/// one directory this component already has open, and a cache would only
	/// add a second way for the figures to go stale.
	///
	/// Whether it is there is logged when it *changes*, not per beat. A backend
	/// silently reporting no CPU or memory looks identical to one reporting
	/// zero, and the difference is a daemon that stopped writing.
	fn host_metrics(&self) -> Option<HostMetrics> {
		let path = std::path::Path::new(&self.data_folder).join(HOST_METRICS_FILE);
		let metrics = std::fs::read_to_string(path)
			.ok()
			.map(|body| decode_host_metrics(&body))
			.filter(|metrics| metrics.is_fresh(now_millis()));

		if self.host_metrics_seen.swap(metrics.is_some(), Ordering::Relaxed) != metrics.is_some() {
			match metrics {
				Some(metrics) => tracing::info!(
					"Đang nhận CPU/RAM từ luna daemon: cpu {:.1}% máy · {:.1}% tiến trình · ram {} MB/{} MB.",
					metrics.system_cpu_percent,
					metrics.process_cpu_percent,
					metrics.ram_used_bytes / 1024 / 1024,
					metrics.ram_max_bytes / 1024 / 1024
				),
				None => tracing::warn!(
					"Không có số liệu CPU/RAM từ luna daemon ({}/{}); \
					 backend sẽ báo cáo 0 cho hai chỉ số này.",
					self.data_folder,
					HOST_METRICS_FILE
				),
			}
		}

		metrics
	}

	/// The registry, shared with whatever draws it.
	#[must_use]
	pub fn registry(&self) -> Arc<Mutex<BackendRegistry>> {
		Arc::clone(&self.registry)
	}

	#[must_use]
	pub fn config(&self) -> &CoreConfig {
		&self.config
	}

	/// The name the AMQP transport binds its queue to; the proxy's answer to the
	/// heartbeat is what fills it in.
	#[must_use]
	pub fn identity(&self) -> &BackendIdentity {
		&self.identity
	}

	/// Drive the broker for a slice of this tick and hand back what arrived.
	///
	/// Called every tick rather than every beat: a plugin message that waited for
	/// the next heartbeat would arrive seconds late, which is the whole latency
	/// budget for something like a server switch.
	pub fn pump_messaging(&self) -> Vec<Vec<u8>> {
		self.transport.pump(&self.identity.name())
	}

	/// Close the broker connection, for plugin unload.
	pub fn shutdown(&self) {
		self.transport.close();
	}

	/// One beat: publish this server's stats and apply what comes back.
	pub fn publish(&self, server: &Server) {
		let stats = self.collect(server);
		let body = encode_form(&stats.to_fields());
		let url = format!(
			"{}?since={}",
			self.config.heartbeat_url(&self.identity.name()),
			self.cursor.load(Ordering::Relaxed)
		);

		if self.config.heartbeat.transport_logging_enabled {
			tracing::debug!("[TX] POST {url} body={body}");
		}

		let timeout = Duration::from_millis(self.config.heartbeat.read_timeout_millis.max(500));
		let response = http::request(
			"POST",
			&url,
			&[
				(
					"Content-Type",
					"application/x-www-form-urlencoded; charset=utf-8",
				),
				(
					"X-Luna-Forwarding-Secret",
					self.config.heartbeat.forwarding_secret.trim(),
				),
			],
			Some(body.as_bytes()),
			timeout,
		);

		match response {
			Ok(reply) if reply.is_ok() => {
				self.note_success();
				self.apply(&reply.body_text());

				// after apply, so the name the queue is built from is the one the
				// proxy just confirmed rather than the one configured locally
				self.sync_messaging_config();
			}
			Ok(reply) => self.note_failure(&format!("proxy trả về status {}", reply.status)),
			Err(error) => self.note_failure(&error),
		}
	}

	/// Fetch the server selector's layout, when it has changed.
	///
	/// The proxy serves the menu over HTTP rather than pushing it on a channel,
	/// because it is the cluster's configuration rather than a message about a
	/// player: a backend with nobody on it still needs the current one, ready
	/// for whoever arrives next. `None` means unchanged or unavailable, and the
	/// caller keeps whatever it already drew.
	pub fn fetch_selector_config(&self) -> Option<Vec<u8>> {
		let url = selector_config_url(&self.config.heartbeat.endpoint);
		let timeout = Duration::from_millis(self.config.heartbeat.read_timeout_millis.max(500));

		let response = http::request(
			"GET",
			&url,
			&[(
				"X-Luna-Forwarding-Secret",
				self.config.heartbeat.forwarding_secret.trim(),
			)],
			None,
			timeout,
		);

		let Ok(reply) = response else {
			return None;
		};

		if !reply.is_ok() || reply.body.is_empty() {
			return None;
		}

		let mut seen = self.selector_body.lock().expect("selector body poisoned");

		if seen.as_deref() == Some(reply.body.as_slice()) {
			return None;
		}

		*seen = Some(reply.body.clone());

		Some(reply.body)
	}

	/// Take the broker settings the proxy publishes for this backend.
	///
	/// The proxy owns them, exactly as it does on Paper: a backend that read its
	/// own file would keep connecting to a broker the cluster had moved off, and
	/// turning AMQP off centrally would never reach it.
	fn sync_messaging_config(&self) {
		let url = self.config.messaging_config_url(&self.identity.name());
		let timeout = Duration::from_millis(self.config.heartbeat.read_timeout_millis.max(500));

		let response = http::request(
			"GET",
			&url,
			&[(
				"X-Luna-Forwarding-Secret",
				self.config.heartbeat.forwarding_secret.trim(),
			)],
			None,
			timeout,
		);

		let Ok(reply) = response else {
			return;
		};

		if !reply.is_ok() {
			return;
		}

		let body = reply.body_text();

		{
			let mut seen = self.messaging_body.lock().expect("messaging body poisoned");

			if seen.as_deref() == Some(body.as_str()) {
				return;
			}

			*seen = Some(body.clone());
		}

		if self.config.heartbeat.transport_logging_enabled {
			tracing::debug!("[RX] messaging-config {body}");
		}

		self.transport
			.update_config(AmqpConfig::from_fields(&decode_form(&body)));
	}

	/// Take the proxy's view of this backend out of a heartbeat reply.
	fn apply(&self, body: &str) {
		if self.config.heartbeat.transport_logging_enabled {
			tracing::debug!("[RX] {body}");
		}

		let frame = decode_frame(body);

		if frame.revision > 0 {
			self.cursor.store(frame.revision, Ordering::Relaxed);
		}

		// The whole registry comes back with every beat, not just an
		// acknowledgement: this is where a backend learns about its neighbours,
		// and the server selector draws itself out of nothing else.
		self.registry
			.lock()
			.expect("backend registry poisoned")
			.apply(&frame);

		// the proxy names this backend in the row it holds for it; that name is
		// what the queue and the placeholders use, so it is worth taking eagerly
		if !frame.identity.is_blank() {
			self.identity.observe(BackendMetadata {
				name: frame.identity.name,
				display_name: frame.identity.display_name,
				accent_color: frame.identity.accent_color,
			});
		}
	}

	fn collect(&self, server: &Server) -> HeartbeatStats {
		let metrics = self.host_metrics();

		HeartbeatStats {
			software: "Pumpkin".into(),
			version: server.get_sys_info().pumpkin_version,
			server_port: i32::from(self.config.heartbeat.server_port),
			uptime_millis: now_millis().saturating_sub(self.boot_epoch_millis) as i64,
			tps: effective_tps(server.get_tps()),
			online_players: i32::try_from(server.get_player_count()).unwrap_or(0),
			max_players: i32::try_from(server.get_max_players()).unwrap_or(0),
			motd: server.get_motd(),
			whitelist_enabled: server.has_whitelist(),
			// Every figure below is heap and process load on the JVM platforms,
			// and this sandbox can measure neither: `get-sys-info` reports the
			// whole machine, which would have a backend claiming the host's
			// 12 GB, and that reaches players through the selector's ram
			// placeholders. The luna daemon samples both per instance from
			// /proc - it is where the console's own columns come from - and
			// leaves them in this plugin's data folder. Zero when it has left
			// nothing recent, which reads as "not reported" rather than as idle.
			system_cpu_usage_percent: metrics.map_or(0.0, |m| m.system_cpu_percent),
			process_cpu_usage_percent: metrics.map_or(0.0, |m| m.process_cpu_percent),
			ram_used_bytes: metrics.map_or(0, |m| m.ram_used_bytes as i64),
			ram_free_bytes: metrics.map_or(0, |m| m.ram_free_bytes() as i64),
			ram_max_bytes: metrics.map_or(0, |m| m.ram_max_bytes as i64),
		}
	}

	fn note_success(&self) {
		let previous = self.consecutive_failures.swap(0, Ordering::Relaxed);

		if previous > 0 {
			tracing::info!("Heartbeat tới proxy đã hoạt động lại sau {previous} lần lỗi.");
		}
	}

	/// Log the first failure and then go quiet, so a proxy that is down for an
	/// hour does not write a line every five seconds.
	fn note_failure(&self, reason: &str) {
		let previous = self.consecutive_failures.fetch_add(1, Ordering::Relaxed);

		if previous == 0 {
			tracing::warn!("Heartbeat tới proxy lỗi: {reason}");
		} else {
			tracing::debug!("Heartbeat tới proxy vẫn lỗi ({}): {reason}", previous + 1);
		}
	}
}

pub fn now_millis() -> u64 {
	SystemTime::now()
		.duration_since(UNIX_EPOCH)
		.map(|elapsed| elapsed.as_millis() as u64)
		.unwrap_or(0)
}
