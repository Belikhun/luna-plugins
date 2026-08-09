//! The messenger's backend half: turn what a player typed into a request, and
//! render whatever the proxy sends back.
//!
//! None of the messaging itself happens here. Which channel a player is talking
//! on, who they last replied to, whether they are muted - all of it is
//! network-wide state that only the proxy can hold, because a backend sees only
//! its own players. So this side is deliberately thin: it publishes presence,
//! forwards commands, correlates the answers, and gives up on one that never
//! comes.
//!
//! Timeouts are swept from the plugin's tick rather than a scheduled thread,
//! which is what the sandbox allows; the deadlines are wall-clock, so a stalled
//! server times a request out when it said it would rather than however much
//! later it got around to it.

use crate::messaging::{Dispatch, MessageBus};
use crate::text::tell;
use luna_core_api::messenger::{
	COMMAND, CommandRequest, CommandType, CURRENT_PROTOCOL, PRESENCE, PresenceMessage,
	PresenceType, RESULT, RequestIds, ResultMessage,
};
use luna_core_api::text::parse;
use luna_permissions::PermissionStore;
use pumpkin_plugin_api::Server;
use pumpkin_plugin_api::player::Player;
use pumpkin_plugin_api::text::TextComponent;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// How long a request waits for its answer before the sender is told.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

/// A control command repeated inside this window is the same keypress twice.
const CONTROL_DEDUP: Duration = Duration::from_millis(250);

/// Direct-target suggestions offered at once.
#[allow(dead_code, reason = "waiting on a suggestion callback; see suggest_targets")]
const MAX_SUGGESTIONS: usize = 20;

/// A request waiting for its answer.
struct Pending {
	player_id: String,
	command_type: CommandType,
	sent_at: Instant,
}

/// The last control command a player sent, for spotting a repeat.
struct RecentControl {
	fingerprint: String,
	at: Instant,
}

/// The backend's messenger.
pub struct Messenger {
	bus: Arc<MessageBus>,
	permissions: Arc<PermissionStore>,
	ids: RequestIds,
	/// The proxy's name for this server, which is what a request is stamped with.
	server_name: Mutex<String>,
	/// Everyone the proxy has told us about, for direct-message completion.
	network_players: Mutex<BTreeMap<String, String>>,
	pending: Mutex<BTreeMap<String, Pending>>,
	recent_control: Mutex<BTreeMap<String, RecentControl>>,
}

impl Messenger {
	/// Build the messenger and put its channels on the bus.
	#[must_use]
	pub fn new(
		bus: Arc<MessageBus>,
		permissions: Arc<PermissionStore>,
		boot_millis: u64,
		server_name: &str,
	) -> Arc<Self> {
		let messenger = Arc::new(Self {
			bus: Arc::clone(&bus),
			permissions,
			ids: RequestIds::new(boot_millis, server_name),
			server_name: Mutex::new(server_name.to_owned()),
			network_players: Mutex::new(BTreeMap::new()),
			pending: Mutex::new(BTreeMap::new()),
			recent_control: Mutex::new(BTreeMap::new()),
		});

		bus.register_outgoing(COMMAND);
		bus.register_outgoing(PRESENCE);

		let results = Arc::clone(&messenger);

		bus.register_incoming(RESULT, move |context| {
			let Ok(result) = ResultMessage::decode(context.payload).inspect_err(|error| {
				tracing::warn!("Messenger result không đọc được: {error:?}");
			}) else {
				return Dispatch::PassThrough;
			};

			results.on_result(context.server, &result);

			Dispatch::Handled
		});

		let presence = Arc::clone(&messenger);

		bus.register_incoming(PRESENCE, move |context| {
			let Ok(message) = PresenceMessage::decode(context.payload).inspect_err(|error| {
				tracing::warn!("Messenger presence không đọc được: {error:?}");
			}) else {
				return Dispatch::PassThrough;
			};

			presence.on_presence(&message);

			Dispatch::Handled
		});

		messenger
	}

	/// Take the name the proxy calls this backend, once the heartbeat learns it.
	pub fn set_server_name(&self, name: &str) {
		*self.server_name.lock().expect("messenger name poisoned") = name.to_owned();
	}

	/// Tell the network a player arrived.
	pub fn publish_join(&self, player: &Player, first_join: bool) {
		let id = player.get_id().to_string();
		let name = player.get_name();

		self.network_players
			.lock()
			.expect("messenger roster poisoned")
			.insert(id.clone(), name.clone());

		let message = PresenceMessage {
			protocol_version: CURRENT_PROTOCOL,
			presence_type: if first_join {
				PresenceType::FirstJoin
			} else {
				PresenceType::Join
			},
			player_id: id,
			player_name: name,
			from_server: String::new(),
			to_server: self.server(),
			first_join,
		};

		self.bus.send(player, PRESENCE, &message.encode());
	}

	/// Tell the network a player left.
	pub fn publish_leave(&self, player: &Player) {
		let id = player.get_id().to_string();

		self.network_players
			.lock()
			.expect("messenger roster poisoned")
			.remove(&id);

		self.recent_control
			.lock()
			.expect("messenger dedup poisoned")
			.remove(&id);

		let message = PresenceMessage {
			protocol_version: CURRENT_PROTOCOL,
			presence_type: PresenceType::Leave,
			player_id: id,
			player_name: player.get_name(),
			from_server: self.server(),
			to_server: String::new(),
			first_join: false,
		};

		self.bus.send(player, PRESENCE, &message.encode());
	}

	/// Forward one command to the proxy. False when nothing was sent.
	pub fn send_command(
		&self,
		player: &Player,
		command_type: CommandType,
		argument: &str,
		target: Option<&str>,
	) -> bool {
		let id = player.get_id().to_string();

		if self.is_repeat(&id, command_type, argument, target) {
			return false;
		}

		let request = CommandRequest {
			protocol_version: CURRENT_PROTOCOL,
			request_id: self.ids.next(),
			command_type,
			sender_id: id.clone(),
			sender_name: player.get_name(),
			sender_server: self.server(),
			argument: argument.to_owned(),
			// the proxy owns which conversation a player is in, so a backend has
			// no hint worth sending: guessing here would fight the proxy's own
			// state the moment the two disagree
			context_hint: None,
			resolved_values: self.exported_values(player, &id, command_type, argument, target),
		};

		if !self.bus.send(player, COMMAND, &request.encode()) {
			return false;
		}

		self.pending.lock().expect("messenger pending poisoned").insert(
			request.request_id,
			Pending {
				player_id: id,
				command_type,
				sent_at: Instant::now(),
			},
		);

		true
	}

	/// Names to offer for a direct-message target.
	///
	/// Nothing calls this yet, and that is a platform gap rather than an
	/// oversight: `command-node` in `pumpkin:plugin@0.1.0` exposes `literal`,
	/// `argument`, `then`, `execute` and `require`, and no way for a plugin to
	/// supply its own suggestions. The roster it reads is kept up to date by the
	/// proxy's presence broadcasts regardless, so this works the day the API
	/// gains one; deleting it would mean deleting the presence handling too, and
	/// then rediscovering why the proxy sends it.
	#[allow(dead_code, reason = "no plugin suggestion callback on this platform yet")]
	#[must_use]
	pub fn suggest_targets(&self, partial: &str, sender: &str) -> Vec<String> {
		let lowered = partial.to_ascii_lowercase();
		let mut names: Vec<String> = self
			.network_players
			.lock()
			.expect("messenger roster poisoned")
			.values()
			.filter(|name| !name.trim().is_empty() && !name.eq_ignore_ascii_case(sender))
			.filter(|name| lowered.is_empty() || name.to_ascii_lowercase().starts_with(&lowered))
			.cloned()
			.collect();

		names.sort();
		names.dedup();
		names.truncate(MAX_SUGGESTIONS);

		names
	}

	/// Give up on requests the proxy never answered.
	///
	/// Called from the plugin's scheduled task. Silence is the failure mode that
	/// matters here: without this, a player whose message vanished into a dead
	/// proxy sees nothing at all and assumes it was sent.
	pub fn tick(&self, server: &Server) {
		let expired: Vec<Pending> = {
			let mut pending = self.pending.lock().expect("messenger pending poisoned");
			let timed_out: Vec<String> = pending
				.iter()
				.filter(|(_, request)| request.sent_at.elapsed() >= REQUEST_TIMEOUT)
				.map(|(id, _)| id.clone())
				.collect();

			timed_out
				.into_iter()
				.filter_map(|id| pending.remove(&id))
				.collect()
		};

		if expired.is_empty() {
			return;
		}

		for request in expired {
			tracing::warn!("Messenger timeout command={}", request.command_type.name());

			if let Some(player) = find(server, &request.player_id) {
				player.send_system_message(
					TextComponent::text(timeout_message(request.command_type)),
					false,
				);
			}
		}
	}

	/// Show a result to the player it is addressed to.
	///
	/// The proxy addresses a result to a player rather than to a connection, and
	/// that player is routinely not the one whose message brought it - an alert
	/// is about somebody else entirely - so the roster is what finds them.
	fn on_result(&self, server: &Server, result: &ResultMessage) {
		if let Some(correlation) = &result.correlation_id {
			self.pending
				.lock()
				.expect("messenger pending poisoned")
				.remove(correlation);
		}

		tracing::debug!(
			"Messenger result={} correlationId={:?}",
			result.result_type.name(),
			result.correlation_id
		);

		if result.mini_message.trim().is_empty() {
			return;
		}

		let Some(player) = find(server, &result.receiver_id) else {
			return;
		};

		// the proxy writes its results in MiniMessage, and `crate::text` is what
		// reads it: colour and all, rather than the words alone
		tell(&player, &parse(&result.mini_message));
	}

	/// Note a player the proxy told us about.
	fn on_presence(&self, message: &PresenceMessage) {
		let mut roster = self.network_players.lock().expect("messenger roster poisoned");

		if message.presence_type == PresenceType::Leave {
			roster.remove(&message.player_id);

			return;
		}

		roster.insert(message.player_id.clone(), message.player_name.clone());
	}

	/// Values the proxy's templates read, resolved from what this side knows.
	///
	/// The JVM resolves the `luckperms_*` set through LuckPerms; on Pumpkin the
	/// permission store *is* what LuckPerms would be, so the same keys are filled
	/// from it. The `vault_*` spellings are the same values under Vault's names,
	/// which is what they are on the JVM too.
	fn exported_values(
		&self,
		player: &Player,
		id: &str,
		command_type: CommandType,
		argument: &str,
		target: Option<&str>,
	) -> BTreeMap<String, String> {
		let name = player.get_name();
		let server = self.server();
		let prefix = self.permissions.prefix(id);
		let suffix = self.permissions.suffix(id);
		let group = self.permissions.group_name(id);

		let mut values = BTreeMap::from([
			("sender_name".to_owned(), name.clone()),
			("player_name".to_owned(), name),
			("player_uuid".to_owned(), id.to_owned()),
			("server_name".to_owned(), server.clone()),
			("sender_server".to_owned(), server),
			("luckperms_prefix".to_owned(), prefix.clone()),
			("luckperms_suffix".to_owned(), suffix.clone()),
			("luckperms_primary_group_name".to_owned(), group.clone()),
			("vault_prefix".to_owned(), prefix),
			("vault_suffix".to_owned(), suffix),
			("vault_primary_group".to_owned(), group),
		]);

		if command_type.has_target() {
			let named = target.unwrap_or(argument);

			values.insert("target_name".to_owned(), named.to_owned());
		}

		values
	}

	/// Whether this is the same control command the player just sent.
	fn is_repeat(
		&self,
		id: &str,
		command_type: CommandType,
		argument: &str,
		target: Option<&str>,
	) -> bool {
		if !command_type.is_control() {
			return false;
		}

		let fingerprint = format!(
			"{}|{argument}|{}",
			command_type.name(),
			target.unwrap_or_default()
		);

		let mut recent = self.recent_control.lock().expect("messenger dedup poisoned");

		if let Some(previous) = recent.get(id)
			&& previous.fingerprint == fingerprint
			&& previous.at.elapsed() < CONTROL_DEDUP
		{
			return true;
		}

		recent.insert(
			id.to_owned(),
			RecentControl {
				fingerprint,
				at: Instant::now(),
			},
		);

		false
	}

	fn server(&self) -> String {
		self.server_name
			.lock()
			.expect("messenger name poisoned")
			.clone()
	}
}

/// A connected player, by uuid.
fn find(server: &Server, id: &str) -> Option<Player> {
	server
		.get_all_players()
		.into_iter()
		.find(|player| player.get_id().to_string() == id)
}

/// What a player is told when their request went unanswered.
fn timeout_message(command_type: CommandType) -> &'static str {
	match command_type {
		CommandType::SendPoke => "❌ Yêu cầu chọc đã hết thời gian chờ.",
		CommandType::SendDirect => "❌ Tin nhắn riêng đã hết thời gian chờ.",
		CommandType::SendReply => "❌ Tin nhắn trả lời đã hết thời gian chờ.",
		CommandType::SendChat => "❌ Tin nhắn chat đã hết thời gian chờ.",
		CommandType::SwitchNetwork | CommandType::SwitchServer | CommandType::SwitchDirect => {
			"❌ Không thể cập nhật kênh nhắn tin lúc này."
		}
	}
}

