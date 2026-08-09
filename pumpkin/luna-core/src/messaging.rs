//! The plugin message bus: one place every cross-server message passes through.
//!
//! Two transports carry the same messages and neither one covers every case. A
//! custom payload rides the addressee's own connection, which is free and
//! immediate but only works while they are connected *here*; the broker reaches
//! a backend nobody is on, at the cost of a round trip through the proxy. So a
//! channel declares which it uses and the bus picks, exactly as the Paper,
//! Fabric and NeoForge buses do.
//!
//! What is different here is the shape rather than the behaviour. A WASM
//! component cannot hold a `Player` between calls - it is a resource, owned and
//! not clonable - so a handler is given the player by reference for the length
//! of the dispatch and the bus stores only the uuid.

use crate::amqp::AmqpTransport;
use luna_core_api::channel::{is_reserved, normalize, travels_as_payload};
use luna_core_api::envelope::PluginMessageEnvelope;
use luna_core_api::identity::BackendIdentity;
use luna_core_api::pending::PendingMessages;
use pumpkin_plugin_api::Server;
use pumpkin_plugin_api::player::Player;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// How long after a player is bound their connection is treated as not ready.
///
/// The JVM buses carry the same window for the same reason: a payload sent in
/// the first moments after login races the client's own channel registration
/// and is dropped on the floor with no error anywhere. Waiting is the only
/// reliable answer; the value is the JVM's.
const SENDER_WARMUP: Duration = Duration::from_millis(1_500);

/// Messages held per player while their connection warms up.
///
/// Far above what a login burst produces and far below anything worth worrying
/// about in memory; see [`PendingMessages`] for why there is a cap at all.
const MAX_PENDING: usize = 64;

/// What a handler did with a message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dispatch {
	Handled,
	PassThrough,
}

/// One message, as its handler sees it.
pub struct MessageContext<'a> {
	/// Who it came from, when they are on this server. Absent for a broker
	/// delivery about somebody who has since moved on.
	pub source: Option<&'a Player>,
	pub channel: &'a str,
	pub payload: &'a [u8],
	/// The server the message arrived on. A handler routinely needs a player
	/// who is *not* the sender - an alert is addressed to somebody else - and
	/// the roster is the only way to reach them.
	pub server: &'a Server,
}

/// A registered listener.
type Handler = Box<dyn Fn(&MessageContext<'_>) -> Dispatch + Send + Sync>;

/// Channels, their listeners, and the two ways out.
pub struct MessageBus {
	incoming: Mutex<BTreeMap<String, Vec<Handler>>>,
	outgoing: Mutex<BTreeSet<String>>,
	/// When each connected player was bound, for the warmup window.
	bound_at: Mutex<HashMap<String, Instant>>,
	/// Messages waiting for a player's window to close, oldest first.
	pending: Mutex<PendingMessages>,
	amqp: Arc<AmqpTransport>,
	identity: BackendIdentity,
	audit: bool,
}

impl MessageBus {
	#[must_use]
	pub fn new(amqp: Arc<AmqpTransport>, identity: BackendIdentity, audit: bool) -> Self {
		Self {
			incoming: Mutex::new(BTreeMap::new()),
			outgoing: Mutex::new(BTreeSet::new()),
			bound_at: Mutex::new(HashMap::new()),
			pending: Mutex::new(PendingMessages::new(MAX_PENDING)),
			amqp,
			identity,
			audit,
		}
	}

	/// Listen on a channel. Several listeners may share one.
	pub fn register_incoming<F>(&self, channel: &str, handler: F)
	where
		F: Fn(&MessageContext<'_>) -> Dispatch + Send + Sync + 'static,
	{
		let Some(name) = self.usable(channel) else {
			return;
		};

		self.incoming
			.lock()
			.expect("bus incoming poisoned")
			.entry(name)
			.or_default()
			.push(Box::new(handler));
	}

	/// Declare a channel this backend sends on.
	///
	/// Sending on an undeclared channel is refused rather than allowed: the
	/// declaration is what a reviewer reads to know what a backend talks about,
	/// and a send that quietly works without one makes that list a lie.
	pub fn register_outgoing(&self, channel: &str) {
		let Some(name) = self.usable(channel) else {
			return;
		};

		self.outgoing.lock().expect("bus outgoing poisoned").insert(name);
	}

	/// Note that a player arrived, starting their warmup window.
	pub fn bind_sender(&self, id: &str) {
		self.bound_at
			.lock()
			.expect("bus senders poisoned")
			.insert(id.to_owned(), Instant::now());
	}

	/// Forget a player who left, and anything queued for them.
	pub fn unbind_sender(&self, id: &str) {
		self.bound_at.lock().expect("bus senders poisoned").remove(id);
		self.pending.lock().expect("bus pending poisoned").forget(id);
	}

	/// Send what was held back while connections were warming up.
	///
	/// Called from the plugin's per-tick task, because a window closes on its own
	/// rather than because anybody asked: without this, a message queued during
	/// login would wait for the next unrelated send to that player, which for a
	/// quiet channel may never come.
	pub fn flush_pending(&self, server: &Server) {
		let ready: Vec<String> = self
			.pending
			.lock()
			.expect("bus pending poisoned")
			.waiting()
			.into_iter()
			.filter(|id| !self.warming_up(id))
			.collect();

		if ready.is_empty() {
			return;
		}

		for player in server.get_all_players() {
			let id = player.get_id().to_string();

			if !ready.contains(&id) {
				continue;
			}

			self.flush_to(&player, &id);
		}
	}

	/// Hand a message to whoever listens on its channel.
	pub fn dispatch_incoming(
		&self,
		server: &Server,
		source: Option<&Player>,
		channel: &str,
		payload: &[u8],
	) -> Dispatch {
		let Some(name) = normalize(channel) else {
			return Dispatch::PassThrough;
		};

		let context = MessageContext {
			source,
			channel: &name,
			payload,
			server,
		};

		let listeners = self.incoming.lock().expect("bus incoming poisoned");

		let Some(handlers) = listeners.get(&name) else {
			return Dispatch::PassThrough;
		};

		let mut result = Dispatch::PassThrough;

		for handler in handlers {
			if handler(&context) == Dispatch::Handled {
				result = Dispatch::Handled;
			}
		}

		result
	}

	/// Decode one broker delivery and dispatch it.
	///
	/// The envelope names its sender, so the roster is searched for them: a
	/// handler that wants to answer needs a connection to answer on, and the
	/// uuid alone is not one. Nobody found is not an error - a message about a
	/// player who has already moved to another backend is still worth handling.
	pub fn dispatch_envelope(&self, server: &Server, body: &[u8]) {
		let envelope = match PluginMessageEnvelope::decode(body) {
			Ok(envelope) => envelope,
			Err(error) => {
				tracing::warn!("Gói tin AMQP không hợp lệ ({} byte): {error:?}", body.len());

				return;
			}
		};

		let source = find_player(
			server,
			&envelope.source_player_id,
			&envelope.source_player_name,
		);

		let result = self.dispatch_incoming(
			server,
			source.as_ref(),
			&envelope.channel,
			&envelope.payload,
		);

		if self.audit {
			tracing::info!(
				"[RX:AMQP] proxy->backend channel={} source={} bytes={} result={result:?}",
				envelope.channel,
				if envelope.source_player_name.is_empty() {
					"unknown"
				} else {
					&envelope.source_player_name
				},
				envelope.payload.len()
			);
		}
	}

	/// Send one message about a player, by whichever transport carries it.
	///
	/// Returns whether it went anywhere. A false is not always a failure: a
	/// payload channel with the addressee still inside their warmup window is a
	/// deliberate drop, and the caller usually has nothing better to do about it
	/// than try again on the next event.
	pub fn send(&self, target: &Player, channel: &str, payload: &[u8]) -> bool {
		let Some(name) = normalize(channel) else {
			tracing::error!("Không gửi được plugin message: channel không hợp lệ '{channel}'.");

			return false;
		};

		if !self
			.outgoing
			.lock()
			.expect("bus outgoing poisoned")
			.contains(&name)
		{
			tracing::error!("Outgoing plugin channel chưa được đăng ký: {name}");

			return false;
		}

		let id = target.get_id().to_string();

		// A message sent into the warmup window is held rather than dropped, and
		// held rather than sent out of order: a client that has not registered the
		// channel yet discards what arrives, and re-sending later behind messages
		// that were queued first would deliver a stale value last.
		if self.warming_up(&id) {
			self.enqueue(&id, &name, payload);

			return false;
		}

		if !self.flush_to(target, &id) {
			self.enqueue(&id, &name, payload);

			return false;
		}

		self.deliver(target, &id, &name, payload)
	}

	/// Send one message, by whichever transport carries its channel.
	fn deliver(&self, target: &Player, id: &str, channel: &str, payload: &[u8]) -> bool {
		if travels_as_payload(channel) && send_payload(target, channel, payload) {
			return true;
		}

		self.publish(channel, id, &target.get_name(), payload)
	}

	/// Hold a message until this player's window closes.
	fn enqueue(&self, id: &str, channel: &str, payload: &[u8]) {
		let dropped = self
			.pending
			.lock()
			.expect("bus pending poisoned")
			.push(id, channel, payload);

		if let Some((channel, _)) = dropped {
			tracing::warn!("Hàng đợi plugin message đầy cho {id}; bỏ gói tin cũ nhất trên {channel}.");
		}
	}

	/// Send this player's held messages, oldest first.
	///
	/// Stops at the first failure and leaves the rest queued, so order survives a
	/// transport that went away mid-flush. True when nothing is left waiting.
	fn flush_to(&self, target: &Player, id: &str) -> bool {
		loop {
			// the lock is taken per message rather than held across the send: a
			// deliver is a host call, and holding it would put every other
			// player's flush behind this one
			let next = self.pending.lock().expect("bus pending poisoned").pop(id);

			let Some((channel, payload)) = next else {
				return true;
			};

			if !self.deliver(target, id, &channel, &payload) {
				self.pending
					.lock()
					.expect("bus pending poisoned")
					.unpop(id, (channel, payload));

				return false;
			}
		}
	}

	/// Put a message on the broker, addressed from this backend.
	fn publish(&self, channel: &str, player_id: &str, player_name: &str, payload: &[u8]) -> bool {
		let envelope = PluginMessageEnvelope::outgoing(
			channel,
			self.identity.name(),
			player_id,
			player_name,
			payload.to_vec(),
		);

		if !self.amqp.publish(&envelope) {
			return false;
		}

		if self.audit {
			tracing::info!(
				"[TX:AMQP] backend->proxy channel={channel} source={player_name} bytes={}",
				payload.len()
			);
		}

		true
	}

	/// Whether this player is still inside their warmup window.
	fn warming_up(&self, id: &str) -> bool {
		self.bound_at
			.lock()
			.expect("bus senders poisoned")
			.get(id)
			.is_some_and(|bound| bound.elapsed() < SENDER_WARMUP)
	}

	/// A channel name a plugin may register, or `None` with the reason logged.
	fn usable(&self, channel: &str) -> Option<String> {
		let Some(name) = normalize(channel) else {
			tracing::error!("Plugin message channel không hợp lệ: '{channel}'.");

			return None;
		};

		if is_reserved(&name) {
			tracing::error!("Plugin message channel thuộc về server: {name}");

			return None;
		}

		Some(name)
	}
}

/// Find a connected player by uuid.
///
/// A `Player` is a WIT resource: handing one to the host consumes it, and none
/// of them can be kept. So anything acting on a player later - a queued screen,
/// a boss bar that needs a second handle for its viewer - holds their uuid and
/// looks them up again here.
pub(crate) fn find_player_by_id(server: &Server, id: &str) -> Option<Player> {
	server
		.get_all_players()
		.into_iter()
		.find(|player| player.get_id().to_string() == id)
}

/// Find a connected player by uuid, falling back to their name.
///
/// The name is worth trying because the uuid a proxy reports is its own view of
/// the player, and an offline-mode backend derives a different one from the same
/// account.
fn find_player(server: &Server, id: &str, name: &str) -> Option<Player> {
	let players = server.get_all_players();

	if !id.is_empty()
		&& let Some(found) = players
			.iter()
			.position(|player| player.get_id().to_string() == id)
	{
		return players.into_iter().nth(found);
	}

	if name.is_empty() {
		return None;
	}

	players
		.into_iter()
		.find(|player| player.get_name().eq_ignore_ascii_case(name))
}

/// Put a message on the player's own connection, if they have a Java one.
///
/// A Bedrock player reaches the server through a different protocol with no
/// custom-payload packet at all, so there is nothing to write; false sends them
/// round by the broker instead.
fn send_payload(target: &Player, channel: &str, payload: &[u8]) -> bool {
	let Some(java) = target.as_java() else {
		return false;
	};

	java.send_custom_payload(channel, payload);

	true
}
