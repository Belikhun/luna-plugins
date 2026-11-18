package dev.belikhun.luna.core.velocity.network.firewall;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Addresses and prefixes as numbers, and a set of prefixes searched by binary
 * search: what a rule's addresses and a country's thousands of prefixes are
 * matched with on every connection.
 *
 * An IPv4-mapped IPv6 address ({@code ::ffff:192.0.2.1}) is read as the IPv4
 * address it carries, so a rule written either way matches it. Text is never
 * resolved as a hostname: only literal addresses parse.
 */
public final class IpRanges {
	private static final BigInteger MAPPED_PREFIX = new BigInteger("ffff00000000", 16);
	private static final BigInteger V4_SPACE = BigInteger.ONE.shiftLeft(32);

	/** One parsed address: its family and value. */
	public record Address(int family, BigInteger value) {
	}

	/** One prefix: family, first and last address, and the canonical text it was written as. */
	public record Range(int family, BigInteger start, BigInteger end, String text) {
		public boolean contains(Address address) {
			return address.family() == family
				&& start.compareTo(address.value()) <= 0
				&& end.compareTo(address.value()) >= 0;
		}
	}

	private final Range[] v4;
	private final Range[] v6;

	private IpRanges(List<Range> ranges) {
		this.v4 = sorted(ranges, 4);
		this.v6 = sorted(ranges, 6);
	}

	/** The set of every prefix that parses; the rest are skipped. */
	public static IpRanges of(Iterable<String> prefixes) {
		List<Range> ranges = new ArrayList<>();

		for (String text : prefixes) {
			Range range = parseRange(text);

			if (range != null) {
				ranges.add(range);
			}
		}

		return new IpRanges(ranges);
	}

	/** An empty set. */
	public static IpRanges empty() {
		return new IpRanges(List.of());
	}

	public int size() {
		return v4.length + v6.length;
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	/** The prefix holding an address, or null. */
	public Range match(Address address) {
		if (address == null) {
			return null;
		}

		Range[] ranges = address.family() == 4
			? v4
			: v6;
		int low = 0;
		int high = ranges.length - 1;

		// the last range starting at or before the address; prefixes in one set
		// may nest, so a miss there falls back to a short scan of earlier ones
		while (low <= high) {
			int middle = (low + high) >>> 1;

			if (ranges[middle].start().compareTo(address.value()) <= 0) {
				low = middle + 1;
			} else {
				high = middle - 1;
			}
		}

		for (int index = high; index >= 0 && index > high - 32; index--) {
			if (ranges[index].contains(address)) {
				return ranges[index];
			}
		}

		return null;
	}

	public boolean contains(Address address) {
		return match(address) != null;
	}

	/** Parse a literal IPv4 or IPv6 address; null for anything else, a hostname included. */
	public static Address parseAddress(String text) {
		String trimmed = text == null
			? ""
			: text.trim();

		if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1);
		}

		int zone = trimmed.indexOf('%');

		if (zone >= 0) {
			trimmed = trimmed.substring(0, zone);
		}

		boolean v4 = trimmed.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
		boolean v6 = trimmed.contains(":") && trimmed.matches("^[0-9a-fA-F:.]{2,45}$");

		if (!v4 && !v6) {
			return null;
		}

		byte[] bytes;

		try {
			// a literal never triggers a lookup; the patterns above keep names out
			bytes = InetAddress.getByName(trimmed).getAddress();
		} catch (UnknownHostException exception) {
			return null;
		}

		BigInteger value = new BigInteger(1, bytes);

		if (bytes.length == 4) {
			return new Address(4, value);
		}

		if (value.shiftRight(32).equals(MAPPED_PREFIX)) {
			return new Address(4, value.mod(V4_SPACE));
		}

		return new Address(6, value);
	}

	/** Parse {@code 192.0.2.0/24}, {@code 2001:db8::/32} or a bare address (a single-address prefix). */
	public static Range parseRange(String text) {
		String trimmed = text == null
			? ""
			: text.trim();

		if (trimmed.isEmpty()) {
			return null;
		}

		int slash = trimmed.indexOf('/');
		Address address = parseAddress(slash < 0 ? trimmed : trimmed.substring(0, slash));

		if (address == null) {
			return null;
		}

		int bits = address.family() == 4
			? 32
			: 128;
		int prefix = bits;

		if (slash >= 0) {
			try {
				prefix = Integer.parseInt(trimmed.substring(slash + 1));
			} catch (NumberFormatException exception) {
				return null;
			}
		}

		if (prefix < 0 || prefix > bits) {
			return null;
		}

		BigInteger hostMask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
		BigInteger start = address.value().andNot(hostMask);
		BigInteger end = start.or(hostMask);
		String canonical = format(address.family(), start) + (prefix == bits ? "" : "/" + prefix);

		return new Range(address.family(), start, end, canonical);
	}

	/** The canonical text of an address. */
	public static String format(int family, BigInteger value) {
		if (family == 4) {
			long number = value.longValue();

			return ((number >> 24) & 0xff) + "." + ((number >> 16) & 0xff) + "." + ((number >> 8) & 0xff) + "." + (number & 0xff);
		}

		byte[] raw = value.toByteArray();
		byte[] bytes = new byte[16];
		int copy = Math.min(raw.length, 16);

		System.arraycopy(raw, raw.length - copy, bytes, 16 - copy, copy);

		try {
			return InetAddress.getByAddress(bytes).getHostAddress().toLowerCase(Locale.ROOT);
		} catch (UnknownHostException exception) {
			return value.toString(16);
		}
	}

	/** The canonical text of a literal address, or null. */
	public static String normalize(String text) {
		Address address = parseAddress(text);

		return address == null
			? null
			: format(address.family(), address.value());
	}

	private static Range[] sorted(List<Range> ranges, int family) {
		Range[] out = ranges.stream()
			.filter(range -> range.family() == family)
			.toArray(Range[]::new);

		Arrays.sort(out, (left, right) -> left.start().compareTo(right.start()));

		return out;
	}
}
