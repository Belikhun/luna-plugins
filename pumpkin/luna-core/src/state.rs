//! The core's runtime state and the beat itself.
//!
//! One tick does the whole exchange: collect what the server says about itself,
//! post it, and take the reply's view of who this backend is. That last part is
//! why the identity is shared rather than computed once - the proxy's answer is
//! what names the AMQP queue and the `current_server` placeholder later.

use crate::amqp::AmqpTransport;
use crate::config::CoreConfig;
use crate::http;
use luna_core_api::heartbeat::{HeartbeatStats, decode_form, effective_tps, encode_form};

use luna_core_api::identity::{BackendIdentity, BackendMetadata};
use luna_core_api::messaging::AmqpConfig;
use pumpkin_plugin_api::Server;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Everything the beat needs, shared with the scheduled task.
pub struct CoreState {
	config: CoreConfig,
	identity: BackendIdentity,
	transport: AmqpTransport,
	boot_epoch_millis: u64,
	/// Where the registry cursor is; the proxy sends what changed after it.
	cursor: AtomicU64,
	/// How many beats in a row failed, so the log says it once rather than each time.
	consecutive_failures: AtomicU64,
	/// The last messaging-config body seen, so an unchanged one is not re-applied.
	messaging_body: Mutex<Option<String>>,
}

impl CoreState {
	#[must_use]
	pub fn new(config: CoreConfig, identity: BackendIdentity) -> Self {
		Self {
			config,
			identity,
			transport: AmqpTransport::new(),
			boot_epoch_millis: now_millis(),
			cursor: AtomicU64::new(0),
			consecutive_failures: AtomicU64::new(0),
			messaging_body: Mutex::new(None),
		}
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

		let fields = decode_form(body);

		if let Some(revision) = fields.get("revision").and_then(|value| value.parse().ok()) {
			self.cursor.store(revision, Ordering::Relaxed);
		}

		// the proxy names this backend in the row it holds for it; that name is
		// what the queue and the placeholders use, so it is worth taking eagerly
		let name = fields
			.get("server_0_name")
			.or_else(|| fields.get("name"))
			.cloned()
			.unwrap_or_default();

		if !name.trim().is_empty() {
			self.identity.observe(BackendMetadata {
				name,
				display_name: fields.get("server_0_display").cloned().unwrap_or_default(),
				accent_color: fields
					.get("server_0_accent_color")
					.cloned()
					.unwrap_or_default(),
			});
		}
	}

	fn collect(&self, server: &Server) -> HeartbeatStats {
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
			// Every figure below is heap and process on the JVM platforms, and the
			// sandbox can read neither: `get-sys-info` reports the whole machine,
			// which would show a backend "using" the host's 12 GB and would reach
			// players through the selector's ram placeholders. Left at zero on
			// purpose; the daemon samples this instance's real RSS from /proc,
			// which is where the console's memory column already comes from.
			system_cpu_usage_percent: 0.0,
			process_cpu_usage_percent: 0.0,
			ram_used_bytes: 0,
			ram_free_bytes: 0,
			ram_max_bytes: 0,
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

fn now_millis() -> u64 {
	SystemTime::now()
		.duration_since(UNIX_EPOCH)
		.map(|elapsed| elapsed.as_millis() as u64)
		.unwrap_or(0)
}
