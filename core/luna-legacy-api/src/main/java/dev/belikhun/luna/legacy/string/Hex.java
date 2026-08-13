package dev.belikhun.luna.legacy.string;

/**
 * Bytes as lowercase hex.
 *
 * `java.util.HexFormat` arrived in Java 17 and this trunk targets 8, so the two
 * places that need it - a shop item's fingerprint id, and anything hashing on
 * this line - share one implementation rather than each growing a loop.
 */
public final class Hex {
	private static final char[] DIGITS = "0123456789abcdef".toCharArray();

	private Hex() {
	}

	/** Lowercase hex, two characters per byte, no separators. */
	public static String encode(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return "";
		}

		char[] out = new char[bytes.length * 2];

		for (int index = 0; index < bytes.length; index += 1) {
			int value = bytes[index] & 0xFF;

			out[index * 2] = DIGITS[value >>> 4];
			out[(index * 2) + 1] = DIGITS[value & 0x0F];
		}

		return new String(out);
	}
}
