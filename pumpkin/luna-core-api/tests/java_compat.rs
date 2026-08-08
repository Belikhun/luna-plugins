//! Byte-for-byte agreement with the JVM encoder.
//!
//! The expected values are not hand-written: they are the output of the real
//! `AmqpPluginMessageEnvelope.encode()` from `luna-core-api`, captured by running
//! it under a JDK. If this test fails, a Pumpkin backend and a Paper one have
//! stopped agreeing on the frame, which no amount of round-tripping inside this
//! crate would catch.

use luna_core_api::envelope::PluginMessageEnvelope;

fn hex(bytes: &[u8]) -> String {
	bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn matches_the_jvm_for_a_normal_envelope() {
	let envelope = PluginMessageEnvelope::outgoing(
		"luna:server_selector_open",
		"pumpkin1",
		"0b1a3f1e-0000-4000-8000-000000000001",
		"Belikhun",
		vec![1, 2, 3, 4],
	);

	assert_eq!(
		hex(&envelope.encode()),
		"0000000100196c756e613a7365727665725f73656c6563746f725f6f70656e000870756d706b696e31\
		 002430623161336631652d303030302d343030302d383030302d303030303030303030303031000842\
		 656c696b68756e00000000000401020304"
			.replace([' ', '\n', '\t'], "")
	);
}

#[test]
fn matches_the_jvm_for_nul_and_supplementary_characters() {
	let envelope = PluginMessageEnvelope {
		protocol_version: 1,
		channel: "a\0b".into(),
		source_server_name: "xin chào".into(),
		source_player_id: "🙂".into(),
		source_player_name: String::new(),
		target_server_name: String::new(),
		payload: Vec::new(),
	};

	assert_eq!(
		hex(&envelope.encode()),
		"00000001000461c08062000978696e206368c3a06f0006eda0bdedb9820000000000000000"
	);
}

#[test]
fn decodes_what_the_jvm_encoded() {
	let bytes: Vec<u8> = (0..)
		.step_by(2)
		.take_while(|i| *i < 74)
		.map(|i| {
			u8::from_str_radix(
				&"00000001000461c08062000978696e206368c3a06f0006eda0bdedb9820000000000000000"
					[i..i + 2],
				16,
			)
			.expect("hex")
		})
		.collect();

	let decoded = PluginMessageEnvelope::decode(&bytes).expect("decodes");

	assert_eq!(decoded.channel, "a\0b");
	assert_eq!(decoded.source_server_name, "xin chào");
	assert_eq!(decoded.source_player_id, "🙂");
	assert!(decoded.payload.is_empty());
}
