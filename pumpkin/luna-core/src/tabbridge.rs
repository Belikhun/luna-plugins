//! Serving TAB's bridge protocol from a Pumpkin backend.
//!
//! TAB runs on the proxy and draws the player list there, but almost everything
//! it wants to draw - a player's rank, their prefix, which world they are in -
//! is only knowable on the backend. On Paper it asks PlaceholderAPI directly;
//! on a platform without one it opens a plugin-message channel to the backend
//! and asks over that instead. This answers that channel, exactly as the Fabric
//! and NeoForge bridges do, so one TAB configuration serves every backend.
//!
//! The wire format is Java's `DataInput`/`DataOutput`, which is why it goes
//! through `luna-core-api`'s reader and writer rather than being hand-rolled:
//! a `writeUTF` is modified UTF-8, and getting that wrong garbles exactly the
//! names that are worth showing.

use luna_core_api::wire::{MessageReader, MessageWriter};
use luna_permissions::PermissionStore;
use pumpkin_plugin_api::player::Player;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

/// The channel TAB opens. The suffix is its protocol revision, not a version.
pub const CHANNEL: &str = "tab:bridge-6";

// Packet ids, as TAB numbers them.
const UPDATE_GAME_MODE: u8 = 1;
const HAS_PERMISSION: u8 = 2;
const SET_WORLD: u8 = 5;
const SET_GROUP: u8 = 6;
const UPDATE_PLACEHOLDER: u8 = 8;
const PLAYER_JOIN_RESPONSE: u8 = 9;

/// Placeholders whose values luna itself knows, without asking anything else.
///
/// A backend with no PlaceholderAPI has no general expansion mechanism, so the
/// set it can answer is finite and worth stating: everything else resolves to
/// the empty string rather than to the literal placeholder text, which is what
/// stops a mis-set TAB config from printing `%some_unknown%` in the player list.
const LUNA_PREFIX: &str = "%luna_";

/// What one player has asked for and what they were last told.
#[derive(Default)]
struct PlayerState {
	/// Placeholder identifier to the refresh interval TAB asked for, in ms.
	requested: BTreeMap<String, i32>,
	/// Values TAB pushed to us from its own expansions.
	pushed: BTreeMap<String, String>,
	/// The last value sent for each placeholder, so an unchanged one is not resent.
	sent: BTreeMap<String, String>,
	world: String,
	group: String,
	game_mode: i32,
}

/// The bridge, shared with the event handler and the refresh task.
pub struct TabBridge {
	permissions: Arc<PermissionStore>,
	players: Mutex<BTreeMap<String, PlayerState>>,
}

impl TabBridge {
	#[must_use]
	pub fn new(permissions: Arc<PermissionStore>) -> Self {
		Self {
			permissions,
			players: Mutex::new(BTreeMap::new()),
		}
	}

	/// Handle one payload TAB sent on its channel.
	///
	/// An undecodable payload is dropped rather than propagated: TAB is a third
	/// party and a protocol it revises is not a reason to fail a player's join.
	pub fn handle(&self, player: &Player, payload: &[u8]) {
		if payload.is_empty() {
			return;
		}

		let mut reader = MessageReader::new(payload);

		let Ok(action) = reader.read_utf() else {
			return;
		};

		match action.as_str() {
			"PlayerJoin" => self.on_join(player, &mut reader),
			"Placeholder" => self.on_placeholder(player, &mut reader),
			"Permission" => self.on_permission(player, &mut reader),
			"Expansion" => self.on_expansion(player, &mut reader),
			"Unload" => self.forget(&player.get_id().to_string()),
			other => tracing::debug!("Bỏ qua TAB bridge action chưa hỗ trợ: {other}"),
		}
	}

	/// Drop a player's state when they leave, so it does not accumulate.
	pub fn forget(&self, id: &str) {
		self.players.lock().expect("tab state poisoned").remove(id);
	}

	/// TAB's opening message: what it wants, and what it should be told now.
	fn on_join(&self, player: &Player, reader: &mut MessageReader<'_>) {
		// a protocol revision we do not branch on, then whether TAB wants the group
		let Ok(_revision) = reader.read_i32() else {
			return;
		};
		let forward_group = reader.read_bool().unwrap_or(false);

		let requested = read_registrations(reader);
		let id = player.get_id().to_string();

		let mut state = PlayerState {
			requested,
			world: world_name(player),
			group: self.permissions.group_name(&id),
			game_mode: game_mode_id(player),
			..PlayerState::default()
		};

		let mut writer = MessageWriter::new();
		writer.write_u8(PLAYER_JOIN_RESPONSE);
		writer.write_utf(&state.world);

		if forward_group {
			writer.write_utf(&state.group);
		}

		writer.write_i32(i32::try_from(state.requested.len()).unwrap_or(0));

		for identifier in state.requested.keys() {
			let value = self.resolve(player, &id, identifier, &state.pushed);

			writer.write_utf(identifier);
			writer.write_utf(&value);
			state.sent.insert(identifier.clone(), value);
		}

		writer.write_i32(state.game_mode);

		self.players
			.lock()
			.expect("tab state poisoned")
			.insert(id, state);

		send(player, writer);
	}

	/// TAB registering one more placeholder after the join.
	fn on_placeholder(&self, player: &Player, reader: &mut MessageReader<'_>) {
		let Ok(identifier) = reader.read_utf() else {
			return;
		};

		// the refresh interval is optional in the protocol
		let refresh = reader.read_i32().unwrap_or(50);
		let id = player.get_id().to_string();
		let pushed = {
			let mut players = self.players.lock().expect("tab state poisoned");
			let state = players.entry(id.clone()).or_default();

			state.requested.insert(identifier.clone(), refresh);
			state.pushed.clone()
		};

		let value = self.resolve(player, &id, &identifier, &pushed);

		self.send_placeholder(player, &id, &identifier, &value);
	}

	/// TAB asking whether this player holds a permission.
	fn on_permission(&self, player: &Player, reader: &mut MessageReader<'_>) {
		let Ok(permission) = reader.read_utf() else {
			return;
		};

		let id = player.get_id().to_string();

		// TAB asks about its own nodes, which luna's store has no opinion on;
		// the server's own answer is the right fallback rather than a flat deny
		let granted = self
			.permissions
			.decide(&id, &permission)
			.unwrap_or_else(|| player.has_permission(&permission));

		let mut writer = MessageWriter::new();
		writer.write_u8(HAS_PERMISSION);
		writer.write_utf(&permission);
		writer.write_bool(granted);

		send(player, writer);
	}

	/// TAB pushing a value it resolved on its own side.
	fn on_expansion(&self, player: &Player, reader: &mut MessageReader<'_>) {
		let (Ok(identifier), Ok(value)) = (reader.read_utf(), reader.read_utf()) else {
			return;
		};

		if identifier.trim().is_empty() {
			return;
		}

		let id = player.get_id().to_string();
		let mut players = self.players.lock().expect("tab state poisoned");

		players.entry(id).or_default().pushed.insert(identifier, value);
	}

	/// Re-send anything whose value has moved since the player was last told.
	///
	/// TAB asks for a refresh interval per placeholder and expects the backend to
	/// push, so this runs from the plugin's own scheduled task. Only differences
	/// go out: the player list is redrawn for every packet, and re-sending an
	/// unchanged prefix twenty times a second is how a tab list starts to flicker.
	pub fn refresh(&self, player: &Player) {
		let id = player.get_id().to_string();

		let (identifiers, pushed, previous_world, previous_group, previous_mode) = {
			let players = self.players.lock().expect("tab state poisoned");

			let Some(state) = players.get(&id) else {
				return;
			};

			(
				state.requested.keys().cloned().collect::<Vec<_>>(),
				state.pushed.clone(),
				state.world.clone(),
				state.group.clone(),
				state.game_mode,
			)
		};

		for identifier in identifiers {
			let value = self.resolve(player, &id, &identifier, &pushed);

			self.send_placeholder(player, &id, &identifier, &value);
		}

		let world = world_name(player);

		if world != previous_world {
			let mut writer = MessageWriter::new();
			writer.write_u8(SET_WORLD);
			writer.write_utf(&world);
			send(player, writer);

			self.with_state(&id, |state| state.world = world);
		}

		let group = self.permissions.group_name(&id);

		if group != previous_group {
			let mut writer = MessageWriter::new();
			writer.write_u8(SET_GROUP);
			writer.write_utf(&group);
			send(player, writer);

			self.with_state(&id, |state| state.group = group);
		}

		let mode = game_mode_id(player);

		if mode != previous_mode {
			let mut writer = MessageWriter::new();
			writer.write_u8(UPDATE_GAME_MODE);
			writer.write_i32(mode);
			send(player, writer);

			self.with_state(&id, |state| state.game_mode = mode);
		}
	}

	/// Send one placeholder, unless the player was already told this value.
	fn send_placeholder(&self, player: &Player, id: &str, identifier: &str, value: &str) {
		{
			let mut players = self.players.lock().expect("tab state poisoned");
			let state = players.entry(id.to_owned()).or_default();

			if state.sent.get(identifier).is_some_and(|last| last == value) {
				return;
			}

			state.sent.insert(identifier.to_owned(), value.to_owned());
		}

		let mut writer = MessageWriter::new();
		writer.write_u8(UPDATE_PLACEHOLDER);
		writer.write_utf(identifier);
		writer.write_utf(value);

		send(player, writer);
	}

	/// What one placeholder is worth for this player, right now.
	///
	/// A value TAB pushed itself wins, because it resolved it against expansions
	/// this side cannot see; otherwise the luna set is answered from the
	/// permission store, and anything else is empty.
	fn resolve(
		&self,
		player: &Player,
		id: &str,
		identifier: &str,
		pushed: &BTreeMap<String, String>,
	) -> String {
		if let Some(value) = pushed.get(identifier) {
			return value.clone();
		}

		if !identifier.starts_with(LUNA_PREFIX) {
			return String::new();
		}

		match identifier {
			"%luna_player_name%" => player.get_name(),
			"%luna_player_group_name%" => self.permissions.group_name(id),
			"%luna_player_prefix%" => self.permissions.prefix(id),
			"%luna_player_suffix%" => self.permissions.suffix(id),
			"%luna_player_display%" => {
				format!("{}{}", self.permissions.prefix(id), player.get_name())
			}
			_ => String::new(),
		}
	}

	fn with_state(&self, id: &str, apply: impl FnOnce(&mut PlayerState)) {
		let mut players = self.players.lock().expect("tab state poisoned");

		apply(players.entry(id.to_owned()).or_default());
	}
}

/// Read the placeholder registrations TAB opens its join message with.
fn read_registrations(reader: &mut MessageReader<'_>) -> BTreeMap<String, i32> {
	let mut requested = BTreeMap::new();

	let Ok(count) = reader.read_i32() else {
		return requested;
	};

	for _ in 0..count.max(0) {
		let (Ok(identifier), Ok(refresh)) = (reader.read_utf(), reader.read_i32()) else {
			break;
		};

		requested.insert(identifier, refresh);
	}

	requested
}

/// Put a built packet on TAB's channel.
///
/// Plugin messages are a Java-edition mechanism, so a Bedrock player simply has
/// nowhere to send this: they are skipped rather than treated as a failure,
/// since TAB never opened the channel with them in the first place.
fn send(player: &Player, writer: MessageWriter) {
	let Some(java) = player.as_java() else {
		return;
	};

	java.send_custom_payload(CHANNEL, &writer.into_vec());
}

/// The world's identifier, which is what TAB matches its per-world rules on.
fn world_name(player: &Player) -> String {
	player.get_world().get_id()
}

/// Vanilla's game-mode numbering, which TAB expects rather than a name.
fn game_mode_id(player: &Player) -> i32 {
	use pumpkin_plugin_api::common::GameMode;

	match player.get_gamemode() {
		GameMode::Survival => 0,
		GameMode::Creative => 1,
		GameMode::Adventure => 2,
		GameMode::Spectator => 3,
	}
}
