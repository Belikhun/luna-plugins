//! The messenger's wire protocol, shared with the JVM backends.
//!
//! A backend does none of the messaging itself: it turns what a player typed
//! into a request, sends it to the proxy, and renders whatever comes back. The
//! chat channel, the mute list and the reply target all live on the proxy,
//! because they are network-wide and a backend only sees its own players.
//!
//! Everything here is the frame layout for that conversation, field for field
//! with `MessengerCommandRequest`, `MessengerPresenceMessage` and
//! `MessengerResultMessage` on the JVM. The order is the protocol; changing one
//! line here without changing it there produces a message the proxy reads as
//! garbage rather than one it rejects.

use crate::wire::{MessageReader, MessageWriter, WireError};
use std::collections::BTreeMap;

/// Channels the messenger speaks on.
pub const COMMAND: &str = "luna:messenger_command";
pub const RESULT: &str = "luna:messenger_result";
pub const SYNC: &str = "luna:messenger_sync";
pub const PRESENCE: &str = "luna:messenger_presence";

/// Protocol version every message carries.
pub const CURRENT_PROTOCOL: i32 = 1;

/// What a player asked the messenger to do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandType {
	SwitchNetwork,
	SwitchServer,
	SwitchDirect,
	SendDirect,
	SendPoke,
	SendChat,
	SendReply,
}

impl CommandType {
	/// The name on the wire, which is the JVM enum constant.
	#[must_use]
	pub fn name(self) -> &'static str {
		match self {
			Self::SwitchNetwork => "SWITCH_NETWORK",
			Self::SwitchServer => "SWITCH_SERVER",
			Self::SwitchDirect => "SWITCH_DIRECT",
			Self::SendDirect => "SEND_DIRECT",
			Self::SendPoke => "SEND_POKE",
			Self::SendChat => "SEND_CHAT",
			Self::SendReply => "SEND_REPLY",
		}
	}

	#[must_use]
	pub fn by_name(value: &str) -> Option<Self> {
		[
			Self::SwitchNetwork,
			Self::SwitchServer,
			Self::SwitchDirect,
			Self::SendDirect,
			Self::SendPoke,
			Self::SendChat,
			Self::SendReply,
		]
		.into_iter()
		.find(|candidate| candidate.name().eq_ignore_ascii_case(value))
	}

	/// Whether this command names somebody to talk to.
	#[must_use]
	pub fn has_target(self) -> bool {
		matches!(self, Self::SwitchDirect | Self::SendDirect | Self::SendPoke)
	}

	/// Whether this only changes which channel a player is talking on.
	///
	/// These are the ones a double-bound key can fire twice in a frame, so they
	/// are the ones worth de-duplicating.
	#[must_use]
	pub fn is_control(self) -> bool {
		matches!(
			self,
			Self::SwitchNetwork | Self::SwitchServer | Self::SwitchDirect
		)
	}
}

/// What the proxy sent back.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResultType {
	Info,
	Error,
	MentionAlert,
	PokeAlert,
	NetworkChat,
	ServerChat,
	DirectChat,
	DirectEcho,
}

impl ResultType {
	#[must_use]
	pub fn name(self) -> &'static str {
		match self {
			Self::Info => "INFO",
			Self::Error => "ERROR",
			Self::MentionAlert => "MENTION_ALERT",
			Self::PokeAlert => "POKE_ALERT",
			Self::NetworkChat => "NETWORK_CHAT",
			Self::ServerChat => "SERVER_CHAT",
			Self::DirectChat => "DIRECT_CHAT",
			Self::DirectEcho => "DIRECT_ECHO",
		}
	}

	#[must_use]
	pub fn by_name(value: &str) -> Option<Self> {
		[
			Self::Info,
			Self::Error,
			Self::MentionAlert,
			Self::PokeAlert,
			Self::NetworkChat,
			Self::ServerChat,
			Self::DirectChat,
			Self::DirectEcho,
		]
		.into_iter()
		.find(|candidate| candidate.name().eq_ignore_ascii_case(value))
	}
}

/// Why a presence message was sent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceType {
	FirstJoin,
	Join,
	Leave,
	Swap,
}

impl PresenceType {
	#[must_use]
	pub fn name(self) -> &'static str {
		match self {
			Self::FirstJoin => "FIRST_JOIN",
			Self::Join => "JOIN",
			Self::Leave => "LEAVE",
			Self::Swap => "SWAP",
		}
	}

	/// `SERVER_SWITCH` is an older spelling of `SWAP` and still arrives.
	#[must_use]
	pub fn by_name(value: &str) -> Option<Self> {
		if value.eq_ignore_ascii_case("SERVER_SWITCH") {
			return Some(Self::Swap);
		}

		[Self::FirstJoin, Self::Join, Self::Leave, Self::Swap]
			.into_iter()
			.find(|candidate| candidate.name().eq_ignore_ascii_case(value))
	}
}

/// Which conversation a player is in.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContextType {
	Network,
	Server,
	Direct,
}

impl ContextType {
	#[must_use]
	pub fn name(self) -> &'static str {
		match self {
			Self::Network => "NETWORK",
			Self::Server => "SERVER",
			Self::Direct => "DIRECT",
		}
	}

	#[must_use]
	pub fn by_name(value: &str) -> Option<Self> {
		[Self::Network, Self::Server, Self::Direct]
			.into_iter()
			.find(|candidate| candidate.name().eq_ignore_ascii_case(value))
	}
}

/// A conversation, and who it is with when it is a direct one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessagingContext {
	pub context_type: ContextType,
	pub direct_target_id: Option<String>,
	pub direct_target_name: Option<String>,
}

impl MessagingContext {
	pub fn write_to(&self, writer: &mut MessageWriter) {
		writer.write_utf(self.context_type.name());

		write_optional(writer, self.direct_target_id.as_deref());
		write_optional(
			writer,
			self.direct_target_name
				.as_deref()
				.filter(|name| !name.trim().is_empty()),
		);
	}

	pub fn read_from(reader: &mut MessageReader<'_>) -> Result<Self, WireError> {
		let context_type =
			ContextType::by_name(&reader.read_utf()?).ok_or(WireError::UnexpectedEnd)?;
		let direct_target_id = read_optional(reader)?;
		let direct_target_name = read_optional(reader)?;

		Ok(Self {
			context_type,
			direct_target_id,
			direct_target_name,
		})
	}
}

/// What a backend asks the proxy to do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandRequest {
	pub protocol_version: i32,
	pub request_id: String,
	pub command_type: CommandType,
	pub sender_id: String,
	pub sender_name: String,
	pub sender_server: String,
	pub argument: String,
	pub context_hint: Option<MessagingContext>,
	pub resolved_values: BTreeMap<String, String>,
}

impl CommandRequest {
	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer.write_i32(self.protocol_version);
		writer.write_utf(&self.request_id);
		writer.write_utf(self.command_type.name());
		writer.write_utf(&self.sender_id);
		writer.write_utf(&self.sender_name);
		writer.write_utf(&self.sender_server);
		writer.write_utf(&self.argument);
		writer.write_bool(self.context_hint.is_some());

		if let Some(context) = &self.context_hint {
			context.write_to(&mut writer);
		}

		write_string_map(&mut writer, &self.resolved_values);

		writer.into_vec()
	}

	pub fn decode(bytes: &[u8]) -> Result<Self, WireError> {
		let mut reader = MessageReader::new(bytes);

		let protocol_version = reader.read_i32()?;
		let request_id = reader.read_utf()?;
		let command_type =
			CommandType::by_name(&reader.read_utf()?).ok_or(WireError::UnexpectedEnd)?;
		let sender_id = reader.read_utf()?;
		let sender_name = reader.read_utf()?;
		let sender_server = reader.read_utf()?;
		let argument = reader.read_utf()?;
		let context_hint = if reader.read_bool()? {
			Some(MessagingContext::read_from(&mut reader)?)
		} else {
			None
		};
		let resolved_values = read_string_map(&mut reader)?;

		Ok(Self {
			protocol_version,
			request_id,
			command_type,
			sender_id,
			sender_name,
			sender_server,
			argument,
			context_hint,
			resolved_values,
		})
	}
}

/// A player arriving at or leaving a backend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PresenceMessage {
	pub protocol_version: i32,
	pub presence_type: PresenceType,
	pub player_id: String,
	pub player_name: String,
	pub from_server: String,
	pub to_server: String,
	pub first_join: bool,
}

impl PresenceMessage {
	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer.write_i32(self.protocol_version);
		writer.write_utf(self.presence_type.name());
		writer.write_utf(&self.player_id);
		writer.write_utf(&self.player_name);
		writer.write_utf(&self.from_server);
		writer.write_utf(&self.to_server);
		writer.write_bool(self.first_join);

		writer.into_vec()
	}

	pub fn decode(bytes: &[u8]) -> Result<Self, WireError> {
		let mut reader = MessageReader::new(bytes);

		Ok(Self {
			protocol_version: reader.read_i32()?,
			presence_type: PresenceType::by_name(&reader.read_utf()?)
				.ok_or(WireError::UnexpectedEnd)?,
			player_id: reader.read_utf()?,
			player_name: reader.read_utf()?,
			from_server: reader.read_utf()?,
			to_server: reader.read_utf()?,
			first_join: reader.read_bool()?,
		})
	}
}

/// What the proxy sends back for a player to read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResultMessage {
	pub protocol_version: i32,
	/// The request this answers, when it answers one; an alert has none.
	pub correlation_id: Option<String>,
	pub receiver_id: String,
	pub result_type: ResultType,
	pub mini_message: String,
	pub metadata: BTreeMap<String, String>,
}

impl ResultMessage {
	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer.write_i32(self.protocol_version);
		write_optional(&mut writer, self.correlation_id.as_deref());
		writer.write_utf(&self.receiver_id);
		writer.write_utf(self.result_type.name());
		writer.write_utf(&self.mini_message);
		write_string_map(&mut writer, &self.metadata);

		writer.into_vec()
	}

	pub fn decode(bytes: &[u8]) -> Result<Self, WireError> {
		let mut reader = MessageReader::new(bytes);

		Ok(Self {
			protocol_version: reader.read_i32()?,
			correlation_id: read_optional(&mut reader)?,
			receiver_id: reader.read_utf()?,
			result_type: ResultType::by_name(&reader.read_utf()?)
				.ok_or(WireError::UnexpectedEnd)?,
			mini_message: reader.read_utf()?,
			metadata: read_string_map(&mut reader)?,
		})
	}
}

/// A present-flag followed by the value, which is how the JVM writes an optional.
fn write_optional(writer: &mut MessageWriter, value: Option<&str>) {
	writer.write_bool(value.is_some());

	if let Some(value) = value {
		writer.write_utf(value);
	}
}

fn read_optional(reader: &mut MessageReader<'_>) -> Result<Option<String>, WireError> {
	if !reader.read_bool()? {
		return Ok(None);
	}

	Ok(Some(reader.read_utf()?))
}

/// A count followed by that many key/value pairs, as `MessengerCodec` writes it.
pub fn write_string_map(writer: &mut MessageWriter, values: &BTreeMap<String, String>) {
	writer.write_i32(i32::try_from(values.len()).unwrap_or(0));

	for (key, value) in values {
		writer.write_utf(key);
		writer.write_utf(value);
	}
}

pub fn read_string_map(reader: &mut MessageReader<'_>) -> Result<BTreeMap<String, String>, WireError> {
	let count = reader.read_i32()?.max(0);
	let mut values = BTreeMap::new();

	for _ in 0..count {
		let key = reader.read_utf()?;
		let value = reader.read_utf()?;

		values.insert(key, value);
	}

	Ok(values)
}

/// Mint request ids the proxy can parse, without a source of randomness.
///
/// The proxy reads a request id with `UUID.fromString`, so it has to be shaped
/// like one; a WASM component has no entropy to make a real v4 from. What it does
/// have is enough to be unique without any: the boot time separates one run from
/// the next, the backend's name separates one server from another, and a counter
/// separates two requests in the same millisecond. Uniqueness is what correlating
/// a reply needs; unpredictability is not, since the id never leaves the cluster.
pub struct RequestIds {
	seed: u64,
	counter: std::sync::atomic::AtomicU64,
}

impl RequestIds {
	#[must_use]
	pub fn new(boot_millis: u64, backend_name: &str) -> Self {
		Self {
			seed: boot_millis ^ (fnv1a(backend_name) << 16),
			counter: std::sync::atomic::AtomicU64::new(0),
		}
	}

	/// The next id, as a v4-shaped UUID string.
	pub fn next(&self) -> String {
		let count = self
			.counter
			.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

		let mut bytes = [0u8; 16];

		bytes[..8].copy_from_slice(&self.seed.to_be_bytes());
		bytes[8..].copy_from_slice(&count.to_be_bytes());

		// the version and variant nibbles RFC 4122 requires of a v4
		bytes[6] = (bytes[6] & 0x0f) | 0x40;
		bytes[8] = (bytes[8] & 0x3f) | 0x80;

		let hex: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();

		format!(
			"{}-{}-{}-{}-{}",
			&hex[0..8],
			&hex[8..12],
			&hex[12..16],
			&hex[16..20],
			&hex[20..32]
		)
	}
}

/// FNV-1a, for spreading a backend name across the seed's upper bits.
fn fnv1a(value: &str) -> u64 {
	let mut hash: u64 = 0xcbf2_9ce4_8422_2325;

	for byte in value.as_bytes() {
		hash ^= u64::from(*byte);
		hash = hash.wrapping_mul(0x1000_0000_01b3);
	}

	hash
}

#[cfg(test)]
mod tests {
	use super::*;

	fn values() -> BTreeMap<String, String> {
		BTreeMap::from([
			("sender_name".to_owned(), "Belikhun".to_owned()),
			("server_name".to_owned(), "lobby".to_owned()),
		])
	}

	#[test]
	fn a_command_survives_the_round_trip() {
		let request = CommandRequest {
			protocol_version: CURRENT_PROTOCOL,
			request_id: "0f9e5d1c-0000-4000-8000-000000000001".to_owned(),
			command_type: CommandType::SendDirect,
			sender_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
			sender_name: "Belikhun".to_owned(),
			sender_server: "pumpkintest".to_owned(),
			argument: "chào bạn".to_owned(),
			context_hint: Some(MessagingContext {
				context_type: ContextType::Direct,
				direct_target_id: Some("00000000-0000-4000-8000-000000000002".to_owned()),
				direct_target_name: Some("Someone".to_owned()),
			}),
			resolved_values: values(),
		};

		assert_eq!(CommandRequest::decode(&request.encode()), Ok(request));
	}

	#[test]
	fn a_command_without_a_context_survives_too() {
		let request = CommandRequest {
			protocol_version: CURRENT_PROTOCOL,
			request_id: "0f9e5d1c-0000-4000-8000-000000000003".to_owned(),
			command_type: CommandType::SendChat,
			sender_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
			sender_name: "Belikhun".to_owned(),
			sender_server: "pumpkintest".to_owned(),
			argument: "hello".to_owned(),
			context_hint: None,
			resolved_values: BTreeMap::new(),
		};

		assert_eq!(CommandRequest::decode(&request.encode()), Ok(request));
	}

	#[test]
	fn presence_survives_the_round_trip() {
		let presence = PresenceMessage {
			protocol_version: CURRENT_PROTOCOL,
			presence_type: PresenceType::Join,
			player_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
			player_name: "Belikhun".to_owned(),
			from_server: String::new(),
			to_server: "pumpkintest".to_owned(),
			first_join: true,
		};

		assert_eq!(PresenceMessage::decode(&presence.encode()), Ok(presence));
	}

	#[test]
	fn a_result_survives_with_and_without_a_correlation() {
		for correlation in [Some("0f9e5d1c-0000-4000-8000-000000000004".to_owned()), None] {
			let result = ResultMessage {
				protocol_version: CURRENT_PROTOCOL,
				correlation_id: correlation,
				receiver_id: "cb732e42-9d20-48df-5f60-21e1e0967d3e".to_owned(),
				result_type: ResultType::DirectChat,
				mini_message: "<green>xin chào</green>".to_owned(),
				metadata: values(),
			};

			assert_eq!(ResultMessage::decode(&result.encode()), Ok(result));
		}
	}

	#[test]
	fn every_name_reads_back_as_itself() {
		for command in [
			CommandType::SwitchNetwork,
			CommandType::SwitchServer,
			CommandType::SwitchDirect,
			CommandType::SendDirect,
			CommandType::SendPoke,
			CommandType::SendChat,
			CommandType::SendReply,
		] {
			assert_eq!(CommandType::by_name(command.name()), Some(command));
		}

		for result in [ResultType::Info, ResultType::PokeAlert, ResultType::DirectEcho] {
			assert_eq!(ResultType::by_name(result.name()), Some(result));
		}

		for presence in [PresenceType::FirstJoin, PresenceType::Swap] {
			assert_eq!(PresenceType::by_name(presence.name()), Some(presence));
		}
	}

	#[test]
	fn the_old_spelling_of_a_swap_still_reads() {
		assert_eq!(PresenceType::by_name("SERVER_SWITCH"), Some(PresenceType::Swap));
	}

	#[test]
	fn an_unknown_name_is_refused_rather_than_guessed() {
		assert_eq!(CommandType::by_name("SEND_SMOKE"), None);
		assert_eq!(ResultType::by_name(""), None);
	}

	#[test]
	fn only_the_commands_that_name_somebody_carry_a_target() {
		assert!(CommandType::SendPoke.has_target());
		assert!(CommandType::SwitchDirect.has_target());
		assert!(!CommandType::SendChat.has_target());
	}

	#[test]
	fn request_ids_are_shaped_like_a_uuid_and_never_repeat() {
		let ids = RequestIds::new(1_786_000_000_000, "pumpkintest");
		let minted: Vec<String> = (0..64).map(|_| ids.next()).collect();

		for id in &minted {
			assert_eq!(id.len(), 36, "{id}");
			assert_eq!(id.as_bytes()[14], b'4', "version nibble: {id}");
			assert!(matches!(id.as_bytes()[19], b'8' | b'9' | b'a' | b'b'), "variant: {id}");
		}

		let unique: std::collections::BTreeSet<&String> = minted.iter().collect();

		assert_eq!(unique.len(), minted.len());
	}

	/// Two backends minting at the same instant must not agree, or a reply meant
	/// for one could be correlated by the other.
	#[test]
	fn two_backends_do_not_mint_the_same_id() {
		let here = RequestIds::new(1_786_000_000_000, "pumpkintest");
		let there = RequestIds::new(1_786_000_000_000, "lobby");

		assert_ne!(here.next(), there.next());
	}
}
