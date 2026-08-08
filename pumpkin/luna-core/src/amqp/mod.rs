//! The broker transport, driven by the game's tick.
//!
//! This is the Pumpkin counterpart of `AmqpConnection` on the JVM side and keeps
//! its shape: the connection owns connect, retry, declare, bind, consume and
//! publish, and the platform supplies only what names the queue and what to do
//! with a delivered body.
//!
//! What is different is that nothing runs on its own. A component has no
//! threads, so there is no IO loop waiting on the socket; [`AmqpTransport::pump`]
//! is the only thing that ever touches it, and it is called from a tick. That
//! makes the game thread the only place plugin messages are dispatched, which is
//! the same guarantee the JVM transports get by hopping to the server thread.

mod codec;
mod session;

use luna_core_api::envelope::PluginMessageEnvelope;
use luna_core_api::messaging::AmqpConfig;
use session::Session;
use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// How long to wait before trying a broker that refused us again.
const RETRY_INTERVAL: Duration = Duration::from_secs(15);

/// What this consumer calls itself, as the broker's console shows it.
const CONSUMER_TAG: &str = "luna-pumpkin";

/// Everything the transport holds, behind one lock.
///
/// One lock rather than several because every operation touches the session and
/// nothing here is concurrent: the tick is the only caller.
#[derive(Default)]
struct State {
	config: AmqpConfig,
	session: Option<Session>,
	/// When a failed connection may be retried.
	retry_after: Option<Instant>,
	/// Bodies received but not yet handed to the game thread.
	inbox: VecDeque<Vec<u8>>,
}

/// The transport, from the plugin's point of view.
#[derive(Default)]
pub struct AmqpTransport {
	state: Mutex<State>,
}

impl AmqpTransport {
	#[must_use]
	pub fn new() -> Self {
		Self::default()
	}

	/// Point the transport at the settings the proxy pushed.
	///
	/// A change tears down whatever is open: the queue name is derived from the
	/// config, so keeping the old connection would leave this backend consuming
	/// from a queue the proxy has stopped publishing to.
	pub fn update_config(&self, config: AmqpConfig) {
		let mut state = self.lock();

		if state.config == config {
			return;
		}

		state.config = config;
		state.retry_after = None;
		Self::drop_session(&mut state);
	}

	#[must_use]
	pub fn is_active(&self) -> bool {
		self.lock().session.is_some()
	}

	/// Drive the connection for this tick and return what arrived.
	///
	/// The caller dispatches the bodies on the game thread, which is why they are
	/// handed back rather than delivered through a callback.
	pub fn pump(&self, server_name: &str) -> Vec<Vec<u8>> {
		let mut state = self.lock();

		if !Self::ensure_ready(&mut state, server_name) {
			return Vec::new();
		}

		let polled = state
			.session
			.as_mut()
			.map(Session::poll)
			.unwrap_or_else(|| Ok(Vec::new()));

		match polled {
			Ok(bodies) => state.inbox.extend(bodies),
			Err(error) => {
				tracing::warn!("Mất kết nối AMQP: {error}");
				Self::fail(&mut state);
			}
		}

		state.inbox.drain(..).collect()
	}

	/// Send one envelope to the proxy's queue.
	pub fn publish(&self, envelope: &PluginMessageEnvelope) -> bool {
		let mut state = self.lock();

		if !state.config.is_configured() {
			return false;
		}

		let queue = state.config.proxy_queue.clone();
		let payload = envelope.encode();

		let Some(session) = state.session.as_mut() else {
			return false;
		};

		match session.publish(&queue, &payload) {
			Ok(()) => true,
			Err(error) => {
				// the socket is gone; the next pump reconnects rather than retrying here
				tracing::warn!("Không gửi được gói tin AMQP: {error}");
				Self::fail(&mut state);

				false
			}
		}
	}

	/// Close whatever is open, quietly.
	pub fn close(&self) {
		let mut state = self.lock();
		Self::drop_session(&mut state);
	}

	/// Connect and set the topology up if it is not already there.
	fn ensure_ready(state: &mut State, server_name: &str) -> bool {
		if state.session.is_some() {
			return true;
		}

		if !state.config.is_configured() {
			return false;
		}

		if state.retry_after.is_some_and(|at| Instant::now() < at) {
			return false;
		}

		let queue = state.config.backend_queue(server_name);
		let opened = Session::open(
			&state.config.uri,
			&state.config.exchange,
			&queue,
			CONSUMER_TAG,
		);

		match opened {
			Ok(session) => {
				tracing::info!(
					"Đã bật AMQP transport cho Pumpkin backend exchange={} queue={} uri={}",
					state.config.exchange,
					queue,
					state.config.masked_uri()
				);

				state.session = Some(session);
				state.retry_after = None;

				true
			}
			Err(error) => {
				tracing::warn!("Không kết nối được AMQP: {error}");
				state.retry_after = Some(Instant::now() + RETRY_INTERVAL);

				false
			}
		}
	}

	/// Drop the session after a failure, holding off the next attempt.
	fn fail(state: &mut State) {
		Self::drop_session(state);
		state.retry_after = Some(Instant::now() + RETRY_INTERVAL);
	}

	/// Close and forget the session, and with it anything half-received.
	fn drop_session(state: &mut State) {
		if let Some(mut session) = state.session.take() {
			session.close();
		}

		state.inbox.clear();
	}

	fn lock(&self) -> std::sync::MutexGuard<'_, State> {
		self.state.lock().expect("amqp state poisoned")
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn an_unconfigured_transport_publishes_nothing() {
		let transport = AmqpTransport::new();
		let envelope = PluginMessageEnvelope::outgoing("a", "b", "c", "d", vec![1]);

		assert!(!transport.publish(&envelope));
		assert!(!transport.is_active());
	}

	#[test]
	fn an_unconfigured_transport_pumps_nothing() {
		let transport = AmqpTransport::new();

		assert!(transport.pump("lobby").is_empty());
	}
}
