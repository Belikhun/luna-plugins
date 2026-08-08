//! The envelope a plugin message travels in when it goes through the broker.
//!
//! This is the same frame `AmqpPluginMessageEnvelope` writes on the JVM side, so
//! a Pumpkin backend and a Paper one can sit on the same exchange. The protocol
//! number is the first field precisely so a mismatched peer can be told apart
//! from a corrupt buffer.

use crate::wire::{MessageReader, MessageWriter, WireError};

/// The protocol version this build writes and understands.
pub const CURRENT_PROTOCOL: i32 = 1;

/// One plugin message, addressed.
///
/// `target_server_name` is empty for backend to proxy, because the proxy is the
/// only thing on the other end of that queue; it carries a name only when the
/// proxy is routing to one specific backend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginMessageEnvelope {
	pub protocol_version: i32,
	pub channel: String,
	pub source_server_name: String,
	pub source_player_id: String,
	pub source_player_name: String,
	pub target_server_name: String,
	pub payload: Vec<u8>,
}

impl PluginMessageEnvelope {
	/// An envelope leaving this backend for the proxy.
	#[must_use]
	pub fn outgoing(
		channel: impl Into<String>,
		source_server_name: impl Into<String>,
		source_player_id: impl Into<String>,
		source_player_name: impl Into<String>,
		payload: Vec<u8>,
	) -> Self {
		Self {
			protocol_version: CURRENT_PROTOCOL,
			channel: channel.into(),
			source_server_name: source_server_name.into(),
			source_player_id: source_player_id.into(),
			source_player_name: source_player_name.into(),
			target_server_name: String::new(),
			payload,
		}
	}

	#[must_use]
	pub fn encode(&self) -> Vec<u8> {
		let mut writer = MessageWriter::new();

		writer.write_i32(self.protocol_version);
		writer.write_utf(&self.channel);
		writer.write_utf(&self.source_server_name);
		writer.write_utf(&self.source_player_id);
		writer.write_utf(&self.source_player_name);
		writer.write_utf(&self.target_server_name);

		// the payload length is written even though the buffer ends here, because
		// that is what the JVM writer does and the frame has to match byte for byte
		writer.write_i32(i32::try_from(self.payload.len()).unwrap_or(i32::MAX));
		writer.write_bytes(&self.payload);

		writer.into_vec()
	}

	pub fn decode(bytes: &[u8]) -> Result<Self, WireError> {
		let mut reader = MessageReader::new(bytes);

		let protocol_version = reader.read_i32()?;
		let channel = reader.read_utf()?;
		let source_server_name = reader.read_utf()?;
		let source_player_id = reader.read_utf()?;
		let source_player_name = reader.read_utf()?;
		let target_server_name = reader.read_utf()?;

		// the JVM side clamps a negative length to zero rather than failing, and a
		// length longer than what is left is a truncated frame, not a huge one
		let declared = reader.read_i32()?.max(0);
		let length = usize::try_from(declared)
			.unwrap_or(0)
			.min(reader.remaining());
		let payload = reader.read_bytes(length)?.to_vec();

		Ok(Self {
			protocol_version,
			channel,
			source_server_name,
			source_player_id,
			source_player_name,
			target_server_name,
			payload,
		})
	}

	/// Whether this envelope came from a peer speaking the same protocol.
	#[must_use]
	pub fn is_supported(&self) -> bool {
		self.protocol_version == CURRENT_PROTOCOL
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn round_trips() {
		let envelope = PluginMessageEnvelope::outgoing(
			"luna:server_selector_open",
			"pumpkin1",
			"0b1a3f1e-0000-4000-8000-000000000001",
			"Belikhun",
			vec![1, 2, 3, 4],
		);

		let decoded = PluginMessageEnvelope::decode(&envelope.encode()).expect("decodes");

		assert_eq!(decoded, envelope);
		assert!(decoded.is_supported());
	}

	#[test]
	fn writes_the_frame_the_jvm_writes() {
		let envelope = PluginMessageEnvelope {
			protocol_version: 1,
			channel: "a".into(),
			source_server_name: String::new(),
			source_player_id: String::new(),
			source_player_name: String::new(),
			target_server_name: String::new(),
			payload: vec![0xFF],
		};

		assert_eq!(
			envelope.encode(),
			vec![
				0, 0, 0, 1, // protocol
				0, 1, b'a', // channel
				0, 0, // sourceServerName
				0, 0, // sourcePlayerId
				0, 0, // sourcePlayerName
				0, 0, // targetServerName
				0, 0, 0, 1, // payload length
				0xFF,
			]
		);
	}

	#[test]
	fn survives_a_truncated_payload_claim() {
		let mut bytes = PluginMessageEnvelope::outgoing("a", "b", "c", "d", vec![1, 2, 3]).encode();
		bytes.truncate(bytes.len() - 2);

		let decoded = PluginMessageEnvelope::decode(&bytes).expect("clamps rather than failing");

		assert_eq!(decoded.payload, vec![1]);
	}

	#[test]
	fn rejects_garbage() {
		assert!(PluginMessageEnvelope::decode(b"not-a-valid-envelope").is_err());
	}
}
