//! The auth backend's wire protocol and its state machine.
//!
//! Authentication itself belongs to the proxy: it holds the passwords, the
//! sessions and the premium probes, and a backend that decided any of it for
//! itself would let a player log in on one server and not another. What a
//! backend owns is the consequence - until the proxy says a player is
//! authenticated, that player may not move, type, build or touch anything.
//!
//! So this module is the two halves that are not platform work: the four
//! channels' payloads, byte-for-byte what `LunaAuthBackendPlugin` reads and
//! writes, and the state each player is in. Both are pure, so the rules a
//! player is judged by are tested rather than inferred from a running server.

use crate::wire::{MessageReader, MessageWriter, WireError};
use std::collections::{BTreeMap, BTreeSet};

/// The channels the auth flow speaks on, matching `AuthChannels` on the JVM.
pub const AUTH_STATE: &str = "luna:auth_state";
pub const COMMAND_REQUEST: &str = "luna:auth_command_request";
pub const COMMAND_RESPONSE: &str = "luna:auth_command_response";
pub const ADMIN_REQUEST: &str = "luna:auth_admin_request";

// Action names, which are the first field of every payload on these channels.
const ACTION_STATE: &str = "state";
const ACTION_RESULT: &str = "auth_result";
const ACTION_RESULT_V2: &str = "auth_result_v2";
const ACTION_SET_SPAWN: &str = "set_spawn";
const ACTION_SYNC_STATE: &str = "sync_state";
const ACTION_LOGIN: &str = "login";
const ACTION_REGISTER: &str = "register";
const ACTION_SET_PREFERENCE: &str = "set_probe_preference";

/// What a player is being asked for while they are not yet authenticated.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PromptMode {
	/// The proxy has not answered yet, so we do not know which to ask for.
	Pending,
	Login,
	Register,
}

/// Where one player stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AuthState {
	pub authenticated: bool,
	pub prompt: PromptMode,
}

impl AuthState {
	/// Nothing known yet, which is what a player joins as.
	#[must_use]
	pub fn pending() -> Self {
		Self {
			authenticated: false,
			prompt: PromptMode::Pending,
		}
	}

	#[must_use]
	pub fn authenticated() -> Self {
		Self {
			authenticated: true,
			prompt: PromptMode::Pending,
		}
	}

	/// Known to be unauthenticated, and which of the two prompts applies.
	#[must_use]
	pub fn unauthenticated(needs_register: bool) -> Self {
		Self {
			authenticated: false,
			prompt: if needs_register {
				PromptMode::Register
			} else {
				PromptMode::Login
			},
		}
	}
}

/// Everyone on this backend, and where they stand.
///
/// A player nobody has heard of is [`AuthState::pending`] rather than absent:
/// the restriction handlers ask about a player before the proxy has ever
/// answered for them, and defaulting to "not authenticated" is what makes a
/// proxy that never replies fail closed.
#[derive(Debug, Clone, Default)]
pub struct AuthStates {
	states: BTreeMap<String, AuthState>,
}

impl AuthStates {
	#[must_use]
	pub fn new() -> Self {
		Self::default()
	}

	#[must_use]
	pub fn state(&self, id: &str) -> AuthState {
		self.states.get(id).copied().unwrap_or_else(AuthState::pending)
	}

	#[must_use]
	pub fn is_authenticated(&self, id: &str) -> bool {
		self.state(id).authenticated
	}

	#[must_use]
	pub fn has_state(&self, id: &str) -> bool {
		self.states.contains_key(id)
	}

	/// Record a state, answering what it was before.
	pub fn set(&mut self, id: &str, state: AuthState) -> AuthState {
		let previous = self.state(id);

		self.states.insert(id.to_owned(), state);

		previous
	}

	pub fn clear(&mut self, id: &str) {
		self.states.remove(id);
	}

	#[must_use]
	pub fn tracked(&self) -> usize {
		self.states.len()
	}
}

/// Which login mode a player picked, and for how long it should stick.
///
/// The four spellings are the proxy's, and it reads them as literals; a
/// backend that sent `online-forever` would have its choice silently ignored.
#[must_use]
pub fn mode_key(premium: bool, remember: bool) -> &'static str {
	match (premium, remember) {
		(true, true) => "online_forever",
		(true, false) => "online",
		(false, true) => "offline_forever",
		(false, false) => "offline",
	}
}

/// Fold the proxy's many spellings of an auth method into the four luna knows.
///
/// The proxy has said `quick-login`, `quickauth` and `quick_login` for the same
/// thing across versions, and the method chooses which congratulation a player
/// reads. An unrecognised one is passed through rather than defaulted, so a
/// method added on the proxy shows up as itself instead of as a wrong message.
#[must_use]
pub fn normalize_auth_method(method: &str) -> String {
	let normalized = method.trim().to_ascii_lowercase();

	match normalized.as_str() {
		"" => "default".to_owned(),
		"quick-login" | "quickauth" | "quick_login" => "quick_login".to_owned(),
		"session-resume" | "session_resume" => "session_resume".to_owned(),
		"login" | "password-login" | "password_login" => "password_login".to_owned(),
		"register" | "register-password" | "register_password" => "register_password".to_owned(),
		_ => normalized,
	}
}

/// The root command of something a player typed, lowercased, without the slash.
///
/// `/Login  hunter2` is `login`. Returns `None` for something that is not a
/// command at all, which the caller refuses rather than guessing about.
#[must_use]
pub fn command_root(line: &str) -> Option<String> {
	let body = line.trim().strip_prefix('/').unwrap_or(line.trim()).trim();

	if body.is_empty() {
		return None;
	}

	let root = body.split_whitespace().next()?;

	Some(root.to_ascii_lowercase())
}

/// Whether an unauthenticated player may run this line.
#[must_use]
pub fn command_allowed(line: &str, allowed: &BTreeSet<String>) -> bool {
	command_root(line).is_some_and(|root| allowed.contains(&root))
}

/// The commands an unauthenticated player may still use.
///
/// Blank entries are dropped and everything is lowercased, so a config written
/// as `["/Login", ""]` behaves the same as one written carefully.
#[must_use]
pub fn allowed_commands(configured: &[String]) -> BTreeSet<String> {
	configured
		.iter()
		.filter_map(|value| command_root(value))
		.collect()
}

/// What the proxy says about one player, on `luna:auth_state`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StateMessage {
	pub player_id: String,
	pub authenticated: bool,
	pub needs_register: bool,
	/// Whether the name looks like it belongs to a paid account, which is what
	/// makes the mode selector worth showing at all.
	pub premium_name_candidate: bool,
	pub has_mode_preference: bool,
	pub username: String,
}

impl StateMessage {
	/// Read one, or `None` when the payload is some other action.
	pub fn decode(payload: &[u8]) -> Result<Option<Self>, WireError> {
		let mut reader = MessageReader::new(payload);

		if reader.read_utf()? != ACTION_STATE {
			return Ok(None);
		}

		Ok(Some(Self {
			player_id: reader.read_utf()?,
			authenticated: reader.read_bool()?,
			needs_register: reader.read_bool()?,
			premium_name_candidate: reader.read_bool()?,
			has_mode_preference: reader.read_bool()?,
			username: reader.read_utf()?,
		}))
	}

	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer
			.write_utf(ACTION_STATE)
			.write_utf(&self.player_id)
			.write_bool(self.authenticated)
			.write_bool(self.needs_register)
			.write_bool(self.premium_name_candidate)
			.write_bool(self.has_mode_preference)
			.write_utf(&self.username);

		writer.into_vec()
	}

	/// The state this message puts the player in.
	#[must_use]
	pub fn to_state(&self) -> AuthState {
		if self.authenticated {
			return AuthState::authenticated();
		}

		AuthState::unauthenticated(self.needs_register)
	}
}

/// The answer to a `/login` or `/register`, on `luna:auth_command_response`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthResult {
	pub player_id: String,
	pub success: bool,
	pub authenticated: bool,
	pub needs_register: bool,
	pub premium_name_candidate: bool,
	pub has_mode_preference: bool,
	/// How they got in, which picks the congratulation. `default` on v1.
	pub method: String,
	pub message: String,
}

impl AuthResult {
	/// Read one, or `None` when the payload is some other action.
	///
	/// Two versions share the channel and differ by one field, so the action
	/// name has to be read before the rest of the frame can be laid out. A
	/// proxy still speaking v1 is not an error; it just names no method.
	pub fn decode(payload: &[u8]) -> Result<Option<Self>, WireError> {
		let mut reader = MessageReader::new(payload);
		let action = reader.read_utf()?;

		if action != ACTION_RESULT && action != ACTION_RESULT_V2 {
			return Ok(None);
		}

		let player_id = reader.read_utf()?;
		let success = reader.read_bool()?;
		let authenticated = reader.read_bool()?;
		let needs_register = reader.read_bool()?;
		let premium_name_candidate = reader.read_bool()?;
		let has_mode_preference = reader.read_bool()?;
		let method = if action == ACTION_RESULT_V2 {
			reader.read_utf()?
		} else {
			"default".to_owned()
		};

		Ok(Some(Self {
			player_id,
			success,
			authenticated,
			needs_register,
			premium_name_candidate,
			has_mode_preference,
			method: normalize_auth_method(&method),
			message: reader.read_utf()?,
		}))
	}

	/// Write one in the v2 shape, which is what the tests round-trip.
	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer
			.write_utf(ACTION_RESULT_V2)
			.write_utf(&self.player_id)
			.write_bool(self.success)
			.write_bool(self.authenticated)
			.write_bool(self.needs_register)
			.write_bool(self.premium_name_candidate)
			.write_bool(self.has_mode_preference)
			.write_utf(&self.method)
			.write_utf(&self.message);

		writer.into_vec()
	}

	/// The state this result puts the player in.
	#[must_use]
	pub fn to_state(&self) -> AuthState {
		if self.authenticated {
			return AuthState::authenticated();
		}

		AuthState::unauthenticated(self.needs_register)
	}
}

/// An operator's request, relayed by the proxy on `luna:auth_admin_request`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdminRequest {
	pub target_id: String,
	pub actor: String,
}

impl AdminRequest {
	/// Read a `set_spawn`, or `None` when the payload is some other action.
	pub fn decode(payload: &[u8]) -> Result<Option<Self>, WireError> {
		let mut reader = MessageReader::new(payload);

		if reader.read_utf()? != ACTION_SET_SPAWN {
			return Ok(None);
		}

		Ok(Some(Self {
			target_id: reader.read_utf()?,
			actor: reader.read_utf()?,
		}))
	}

	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer
			.write_utf(ACTION_SET_SPAWN)
			.write_utf(&self.target_id)
			.write_utf(&self.actor);

		writer.into_vec()
	}
}

/// What this backend asks the proxy to do, on `luna:auth_command_request`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthRequest {
	/// "Tell me where this player stands", sent on join and while waiting.
	SyncState,
	Login { password: String },
	Register { password: String, confirm: String },
	/// "They picked this login mode"; see [`mode_key`].
	SetPreference { mode: String },
}

impl AuthRequest {
	/// Write the request for one player.
	#[must_use]
	pub fn encode(&self, player_id: &str, username: &str) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer
			.write_utf(self.action())
			.write_utf(player_id)
			.write_utf(username);

		match self {
			Self::SyncState => {}
			Self::Login { password } => {
				writer.write_utf(password);
			}
			Self::Register { password, confirm } => {
				writer.write_utf(password).write_utf(confirm);
			}
			Self::SetPreference { mode } => {
				writer.write_utf(mode);
			}
		}

		writer.into_vec()
	}

	/// The action name this request is written under.
	#[must_use]
	pub fn action(&self) -> &'static str {
		match self {
			Self::SyncState => ACTION_SYNC_STATE,
			Self::Login { .. } => ACTION_LOGIN,
			Self::Register { .. } => ACTION_REGISTER,
			Self::SetPreference { .. } => ACTION_SET_PREFERENCE,
		}
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn state_message() -> StateMessage {
		StateMessage {
			player_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
			authenticated: false,
			needs_register: true,
			premium_name_candidate: true,
			has_mode_preference: false,
			username: "Belikhun".to_owned(),
		}
	}

	fn auth_result() -> AuthResult {
		AuthResult {
			player_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
			success: true,
			authenticated: true,
			needs_register: false,
			premium_name_candidate: true,
			has_mode_preference: true,
			method: "quick_login".to_owned(),
			message: "Đã xác thực".to_owned(),
		}
	}

	#[test]
	fn a_state_message_round_trips() {
		let message = state_message();

		assert_eq!(
			StateMessage::decode(&message.encode()),
			Ok(Some(message))
		);
	}

	#[test]
	fn a_result_round_trips_through_v2() {
		let result = auth_result();

		assert_eq!(AuthResult::decode(&result.encode()), Ok(Some(result)));
	}

	/// The v1 frame carries no method field, so laying it out as v2 would read
	/// the message into the method and then run off the end of the buffer.
	#[test]
	fn a_v1_result_is_read_without_a_method() {
		let mut writer = MessageWriter::new();

		writer
			.write_utf("auth_result")
			.write_utf("id")
			.write_bool(true)
			.write_bool(true)
			.write_bool(false)
			.write_bool(false)
			.write_bool(false)
			.write_utf("xong");

		let decoded = AuthResult::decode(&writer.into_vec())
			.expect("decodes")
			.expect("is a result");

		assert_eq!(decoded.method, "default");
		assert_eq!(decoded.message, "xong");
	}

	#[test]
	fn another_action_on_the_channel_is_not_ours() {
		let mut writer = MessageWriter::new();
		writer.write_utf("something_else").write_utf("id");

		assert_eq!(StateMessage::decode(&writer.as_slice().to_vec()), Ok(None));
		assert_eq!(AuthResult::decode(writer.as_slice()), Ok(None));
		assert_eq!(AdminRequest::decode(writer.as_slice()), Ok(None));
	}

	#[test]
	fn a_truncated_frame_is_an_error_rather_than_a_guess() {
		let mut writer = MessageWriter::new();
		writer.write_utf("state").write_utf("id");

		assert_eq!(
			StateMessage::decode(&writer.into_vec()),
			Err(WireError::UnexpectedEnd)
		);
	}

	#[test]
	fn an_admin_request_round_trips() {
		let request = AdminRequest {
			target_id: "id".to_owned(),
			actor: "Belikhun".to_owned(),
		};

		assert_eq!(AdminRequest::decode(&request.encode()), Ok(Some(request)));
	}

	#[test]
	fn a_request_writes_its_action_then_the_player() {
		let encoded = AuthRequest::Login {
			password: "hunter2".to_owned(),
		}
		.encode("id", "Belikhun");

		let mut reader = MessageReader::new(&encoded);

		assert_eq!(reader.read_utf(), Ok("login".to_owned()));
		assert_eq!(reader.read_utf(), Ok("id".to_owned()));
		assert_eq!(reader.read_utf(), Ok("Belikhun".to_owned()));
		assert_eq!(reader.read_utf(), Ok("hunter2".to_owned()));
		assert_eq!(reader.remaining(), 0);
	}

	#[test]
	fn a_register_carries_both_attempts() {
		let encoded = AuthRequest::Register {
			password: "a".to_owned(),
			confirm: "b".to_owned(),
		}
		.encode("id", "Belikhun");

		let mut reader = MessageReader::new(&encoded);

		for expected in ["register", "id", "Belikhun", "a", "b"] {
			assert_eq!(reader.read_utf(), Ok(expected.to_owned()));
		}
	}

	#[test]
	fn a_sync_carries_nothing_beyond_the_player() {
		let encoded = AuthRequest::SyncState.encode("id", "Belikhun");
		let mut reader = MessageReader::new(&encoded);

		assert_eq!(reader.read_utf(), Ok("sync_state".to_owned()));
		reader.read_utf().expect("id");
		reader.read_utf().expect("name");
		assert_eq!(reader.remaining(), 0);
	}

	#[test]
	fn an_unknown_player_is_pending_rather_than_authenticated() {
		let states = AuthStates::new();

		assert!(!states.is_authenticated("nobody"));
		assert_eq!(states.state("nobody").prompt, PromptMode::Pending);
		assert!(!states.has_state("nobody"));
	}

	#[test]
	fn a_state_message_decides_which_prompt_applies() {
		let mut message = state_message();

		assert_eq!(message.to_state(), AuthState::unauthenticated(true));

		message.needs_register = false;
		assert_eq!(message.to_state().prompt, PromptMode::Login);

		message.authenticated = true;
		assert!(message.to_state().authenticated);
	}

	#[test]
	fn setting_a_state_answers_what_it_replaced() {
		let mut states = AuthStates::new();

		assert_eq!(states.set("a", AuthState::authenticated()), AuthState::pending());
		assert!(states.is_authenticated("a"));

		states.clear("a");
		assert!(!states.is_authenticated("a"));
		assert_eq!(states.tracked(), 0);
	}

	#[test]
	fn the_four_mode_keys_are_the_proxys_spellings() {
		assert_eq!(mode_key(true, true), "online_forever");
		assert_eq!(mode_key(true, false), "online");
		assert_eq!(mode_key(false, true), "offline_forever");
		assert_eq!(mode_key(false, false), "offline");
	}

	#[test]
	fn every_spelling_of_a_method_folds_to_one() {
		assert_eq!(normalize_auth_method("Quick-Login"), "quick_login");
		assert_eq!(normalize_auth_method("quickauth"), "quick_login");
		assert_eq!(normalize_auth_method("session-resume"), "session_resume");
		assert_eq!(normalize_auth_method("password-login"), "password_login");
		assert_eq!(normalize_auth_method("register"), "register_password");
		assert_eq!(normalize_auth_method("  "), "default");
	}

	#[test]
	fn an_unknown_method_is_passed_through() {
		assert_eq!(normalize_auth_method("Totp"), "totp");
	}

	#[test]
	fn a_command_root_drops_the_slash_and_the_arguments() {
		assert_eq!(command_root("/Login  hunter2"), Some("login".to_owned()));
		assert_eq!(command_root("register a b"), Some("register".to_owned()));
		assert_eq!(command_root("/"), None);
		assert_eq!(command_root("   "), None);
	}

	#[test]
	fn only_configured_commands_are_allowed() {
		let allowed = allowed_commands(&[
			"login".to_owned(),
			"/Register".to_owned(),
			String::new(),
			"  ".to_owned(),
		]);

		assert_eq!(allowed.len(), 2);
		assert!(command_allowed("/login hunter2", &allowed));
		assert!(command_allowed("/REGISTER a b", &allowed));
		assert!(!command_allowed("/tp 0 0 0", &allowed));
		assert!(!command_allowed("/", &allowed));
	}

	/// `/loginx` must not pass because `login` is allowed; the match is on the
	/// whole root, not a prefix of it.
	#[test]
	fn a_command_that_merely_starts_the_same_is_refused() {
		let allowed = allowed_commands(&["login".to_owned()]);

		assert!(!command_allowed("/loginx pw", &allowed));
	}
}
