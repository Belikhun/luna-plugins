package dev.belikhun.luna.legacy.forwarding;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The handful of Minecraft protocol primitives the forwarding exchange needs,
 * over a plain byte array.
 *
 * Deliberately not netty: this lives in the platform-free module so the codec
 * beside it can be unit-tested against captured proxy bytes with no server and
 * no Minecraft on the classpath. The mod converts a `ByteBuf` to bytes at the
 * boundary and everything below that boundary is ordinary Java.
 *
 * Reading is bounds-checked on every primitive rather than trusting the length
 * prefixes, because the bytes arrive from the network: a truncated or hostile
 * payload has to fail as a refused login, never as an index out of bounds
 * halfway through mutating a login handler.
 */
public final class ProtocolBuffer {
	/** Longest string the protocol allows anywhere in this exchange. */
	private static final int MAX_STRING_LENGTH = 32767;

	/** A varint is at most five bytes; a sixth means a malformed or hostile one. */
	private static final int MAX_VARINT_BYTES = 5;

	private final byte[] bytes;
	private int cursor;

	public ProtocolBuffer(byte[] bytes) {
		this.bytes = bytes == null ? new byte[0] : bytes;
	}

	public int remaining() {
		return bytes.length - cursor;
	}

	public int position() {
		return cursor;
	}

	public int readVarInt() {
		int value = 0;
		int read = 0;

		while (true) {
			if (read >= MAX_VARINT_BYTES) {
				throw new LunaLegacyException("VarInt quá dài trong gói forwarding.");
			}

			byte current = readByte();

			value |= (current & 0x7F) << (read * 7);
			read += 1;

			if ((current & 0x80) == 0) {
				return value;
			}
		}
	}

	public byte readByte() {
		require(1);

		return bytes[cursor++];
	}

	public boolean readBoolean() {
		return readByte() != 0;
	}

	public String readString() {
		int length = readVarInt();

		if (length < 0 || length > MAX_STRING_LENGTH) {
			throw new LunaLegacyException("Độ dài chuỗi không hợp lệ trong gói forwarding: " + length);
		}

		require(length);

		String value = new String(bytes, cursor, length, StandardCharsets.UTF_8);
		cursor += length;

		return value;
	}

	/** Two big-endian longs, most significant first, as the protocol writes them. */
	public UUID readUuid() {
		return new UUID(readLong(), readLong());
	}

	public long readLong() {
		require(8);

		long value = 0;

		for (int index = 0; index < 8; index += 1) {
			value = (value << 8) | (bytes[cursor + index] & 0xFFL);
		}

		cursor += 8;

		return value;
	}

	public byte[] readBytes(int length) {
		require(length);

		byte[] out = new byte[length];
		System.arraycopy(bytes, cursor, out, 0, length);
		cursor += length;

		return out;
	}

	/** Everything from the cursor to the end. */
	public byte[] readRemaining() {
		return readBytes(remaining());
	}

	private void require(int count) {
		if (count < 0 || remaining() < count) {
			throw new LunaLegacyException(
				"Gói forwarding bị cắt cụt: cần " + count + " byte, còn " + remaining() + "."
			);
		}
	}

	// ------------------------------------------------------------------ writing

	/** Build a protocol byte sequence; the write half needs no cursor. */
	public static final class Writer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		public Writer writeVarInt(int value) {
			int remaining = value;

			while (true) {
				if ((remaining & ~0x7F) == 0) {
					out.write(remaining);
					return this;
				}

				out.write((remaining & 0x7F) | 0x80);
				remaining >>>= 7;
			}
		}

		public Writer writeByte(int value) {
			out.write(value);

			return this;
		}

		public Writer writeString(String value) {
			byte[] encoded = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);

			writeVarInt(encoded.length);
			out.write(encoded, 0, encoded.length);

			return this;
		}

		public Writer writeBytes(byte[] value) {
			if (value != null) {
				out.write(value, 0, value.length);
			}

			return this;
		}

		public byte[] toBytes() {
			return out.toByteArray();
		}
	}
}
