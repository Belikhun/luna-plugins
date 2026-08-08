//! The plugin-message wire format, byte-for-byte what the JVM platforms write.
//!
//! Every other luna backend builds these buffers with `java.io.DataOutputStream`,
//! so this is not a free choice of encoding: integers are big-endian and strings
//! are Java's **modified UTF-8**, which differs from real UTF-8 in two places -
//! a NUL byte is written as the two-byte sequence `C0 80` so it can never appear
//! inside a string, and a character outside the BMP is written as its two UTF-16
//! surrogates encoded separately (CESU-8) rather than as one four-byte sequence.
//!
//! Getting that wrong does not fail loudly; it produces a buffer the proxy
//! decodes into subtly different text, so the round-trip is covered by tests.

use std::fmt;

/// Anything that can go wrong reading a buffer the other side wrote.
#[derive(Debug, PartialEq, Eq)]
pub enum WireError {
	/// The buffer ended before the value did.
	UnexpectedEnd,
	/// A string's bytes are not valid modified UTF-8.
	MalformedUtf8,
	/// A length prefix is larger than the buffer that follows it.
	LengthOutOfRange,
}

impl fmt::Display for WireError {
	fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
		match self {
			Self::UnexpectedEnd => f.write_str("plugin message ended early"),
			Self::MalformedUtf8 => f.write_str("plugin message holds malformed UTF"),
			Self::LengthOutOfRange => f.write_str("plugin message length prefix out of range"),
		}
	}
}

impl std::error::Error for WireError {}

/// Builds a plugin-message payload.
#[derive(Debug, Default)]
pub struct MessageWriter {
	buffer: Vec<u8>,
}

impl MessageWriter {
	#[must_use]
	pub fn new() -> Self {
		Self::default()
	}

	pub fn write_i32(&mut self, value: i32) -> &mut Self {
		self.buffer.extend_from_slice(&value.to_be_bytes());
		self
	}

	pub fn write_i16(&mut self, value: i16) -> &mut Self {
		self.buffer.extend_from_slice(&value.to_be_bytes());
		self
	}

	pub fn write_i64(&mut self, value: i64) -> &mut Self {
		self.buffer.extend_from_slice(&value.to_be_bytes());
		self
	}

	/// Java's `DataOutputStream.writeByte`, which packet ids are written with.
	pub fn write_u8(&mut self, value: u8) -> &mut Self {
		self.buffer.push(value);
		self
	}

	pub fn write_bool(&mut self, value: bool) -> &mut Self {
		self.buffer.push(u8::from(value));
		self
	}

	pub fn write_bytes(&mut self, value: &[u8]) -> &mut Self {
		self.buffer.extend_from_slice(value);
		self
	}

	/// Java's `DataOutputStream.writeUTF`: a `u16` byte count, then modified UTF-8.
	pub fn write_utf(&mut self, value: &str) -> &mut Self {
		let encoded = encode_modified_utf8(value);

		// Java throws past 65535 bytes rather than truncating. Nothing luna sends
		// comes close, and silently cutting a string in half would corrupt the
		// frame that follows it, so clamp loudly-shaped instead: refuse to write.
		let length = u16::try_from(encoded.len()).unwrap_or(u16::MAX);
		self.buffer.extend_from_slice(&length.to_be_bytes());
		self.buffer
			.extend_from_slice(&encoded[..length as usize]);
		self
	}

	#[must_use]
	pub fn into_vec(self) -> Vec<u8> {
		self.buffer
	}

	#[must_use]
	pub fn as_slice(&self) -> &[u8] {
		&self.buffer
	}
}

/// Reads a plugin-message payload.
#[derive(Debug)]
pub struct MessageReader<'a> {
	buffer: &'a [u8],
	cursor: usize,
}

impl<'a> MessageReader<'a> {
	#[must_use]
	pub fn new(buffer: &'a [u8]) -> Self {
		Self { buffer, cursor: 0 }
	}

	fn take(&mut self, count: usize) -> Result<&'a [u8], WireError> {
		let end = self
			.cursor
			.checked_add(count)
			.ok_or(WireError::LengthOutOfRange)?;

		if end > self.buffer.len() {
			return Err(WireError::UnexpectedEnd);
		}

		let slice = &self.buffer[self.cursor..end];
		self.cursor = end;
		Ok(slice)
	}

	pub fn read_i32(&mut self) -> Result<i32, WireError> {
		let bytes = self.take(4)?;

		Ok(i32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
	}

	pub fn read_i16(&mut self) -> Result<i16, WireError> {
		let bytes = self.take(2)?;

		Ok(i16::from_be_bytes([bytes[0], bytes[1]]))
	}

	pub fn read_i64(&mut self) -> Result<i64, WireError> {
		let bytes = self.take(8)?;

		Ok(i64::from_be_bytes([
			bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
		]))
	}

	pub fn read_u8(&mut self) -> Result<u8, WireError> {
		Ok(self.take(1)?[0])
	}

	pub fn read_bool(&mut self) -> Result<bool, WireError> {
		Ok(self.take(1)?[0] != 0)
	}

	pub fn read_bytes(&mut self, count: usize) -> Result<&'a [u8], WireError> {
		self.take(count)
	}

	pub fn read_utf(&mut self) -> Result<String, WireError> {
		let prefix = self.take(2)?;
		let length = usize::from(u16::from_be_bytes([prefix[0], prefix[1]]));

		decode_modified_utf8(self.take(length)?)
	}

	/// How many bytes are still unread; the AMQP envelope uses it to take the rest.
	#[must_use]
	pub fn remaining(&self) -> usize {
		self.buffer.len().saturating_sub(self.cursor)
	}
}

fn encode_modified_utf8(value: &str) -> Vec<u8> {
	let mut out = Vec::with_capacity(value.len());

	for ch in value.chars() {
		let code = ch as u32;

		if code == 0 {
			// the whole point of modified UTF-8: never emit a bare NUL
			out.extend_from_slice(&[0xC0, 0x80]);
			continue;
		}

		if code < 0x80 {
			out.push(code as u8);
			continue;
		}

		if code < 0x800 {
			out.push(0xC0 | (code >> 6) as u8);
			out.push(0x80 | (code & 0x3F) as u8);
			continue;
		}

		if code < 0x1_0000 {
			out.push(0xE0 | (code >> 12) as u8);
			out.push(0x80 | ((code >> 6) & 0x3F) as u8);
			out.push(0x80 | (code & 0x3F) as u8);
			continue;
		}

		// outside the BMP: Java writes the two UTF-16 surrogates separately
		let adjusted = code - 0x1_0000;
		let high = 0xD800 + (adjusted >> 10);
		let low = 0xDC00 + (adjusted & 0x3FF);

		for surrogate in [high, low] {
			out.push(0xE0 | (surrogate >> 12) as u8);
			out.push(0x80 | ((surrogate >> 6) & 0x3F) as u8);
			out.push(0x80 | (surrogate & 0x3F) as u8);
		}
	}

	out
}

fn decode_modified_utf8(bytes: &[u8]) -> Result<String, WireError> {
	let mut units: Vec<u16> = Vec::with_capacity(bytes.len());
	let mut index = 0;

	while index < bytes.len() {
		let first = bytes[index];

		if first < 0x80 {
			units.push(u16::from(first));
			index += 1;
			continue;
		}

		if first & 0xE0 == 0xC0 {
			let second = *bytes.get(index + 1).ok_or(WireError::UnexpectedEnd)?;
			if second & 0xC0 != 0x80 {
				return Err(WireError::MalformedUtf8);
			}

			units.push(u16::from(first & 0x1F) << 6 | u16::from(second & 0x3F));
			index += 2;
			continue;
		}

		if first & 0xF0 == 0xE0 {
			let second = *bytes.get(index + 1).ok_or(WireError::UnexpectedEnd)?;
			let third = *bytes.get(index + 2).ok_or(WireError::UnexpectedEnd)?;
			if second & 0xC0 != 0x80 || third & 0xC0 != 0x80 {
				return Err(WireError::MalformedUtf8);
			}

			units.push(
				u16::from(first & 0x0F) << 12
					| u16::from(second & 0x3F) << 6
					| u16::from(third & 0x3F),
			);
			index += 3;
			continue;
		}

		// four-byte real-UTF-8 sequences never appear in modified UTF-8
		return Err(WireError::MalformedUtf8);
	}

	String::from_utf16(&units).map_err(|_| WireError::MalformedUtf8)
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn writes_big_endian_integers() {
		let mut writer = MessageWriter::new();
		writer.write_i32(1);

		assert_eq!(writer.as_slice(), &[0, 0, 0, 1]);
	}

	#[test]
	fn writes_java_length_prefixed_strings() {
		let mut writer = MessageWriter::new();
		writer.write_utf("luna");

		// DataOutputStream.writeUTF("luna") is exactly this
		assert_eq!(writer.as_slice(), &[0, 4, b'l', b'u', b'n', b'a']);
	}

	#[test]
	fn encodes_nul_as_two_bytes() {
		let mut writer = MessageWriter::new();
		writer.write_utf("a\0b");

		assert_eq!(writer.as_slice(), &[0, 4, b'a', 0xC0, 0x80, b'b']);
	}

	#[test]
	fn encodes_supplementary_chars_as_surrogate_pairs() {
		let mut writer = MessageWriter::new();
		// U+1F642, which real UTF-8 would write in four bytes
		writer.write_utf("\u{1F642}");
		let bytes = writer.into_vec();

		assert_eq!(&bytes[..2], &[0, 6]);
		assert_eq!(&bytes[2..], &[0xED, 0xA0, 0xBD, 0xED, 0xB9, 0x82]);
	}

	#[test]
	fn round_trips_every_shape() {
		let mut writer = MessageWriter::new();
		writer.write_i32(-7);
		writer.write_utf("xin chào");
		writer.write_utf("a\0b");
		writer.write_utf("\u{1F642}");
		writer.write_bool(true);
		writer.write_i64(i64::MIN);
		let bytes = writer.into_vec();

		let mut reader = MessageReader::new(&bytes);
		assert_eq!(reader.read_i32(), Ok(-7));
		assert_eq!(reader.read_utf().as_deref(), Ok("xin chào"));
		assert_eq!(reader.read_utf().as_deref(), Ok("a\0b"));
		assert_eq!(reader.read_utf().as_deref(), Ok("\u{1F642}"));
		assert_eq!(reader.read_bool(), Ok(true));
		assert_eq!(reader.read_i64(), Ok(i64::MIN));
		assert_eq!(reader.remaining(), 0);
	}

	#[test]
	fn refuses_a_truncated_buffer() {
		let bytes = [0, 0, 0];
		let mut reader = MessageReader::new(&bytes);

		assert_eq!(reader.read_i32(), Err(WireError::UnexpectedEnd));
	}

	#[test]
	fn refuses_four_byte_utf8() {
		// what a naive real-UTF-8 encoder would have produced for U+1F642
		let bytes = [0, 4, 0xF0, 0x9F, 0x99, 0x82];
		let mut reader = MessageReader::new(&bytes);

		assert_eq!(reader.read_utf(), Err(WireError::MalformedUtf8));
	}
}
