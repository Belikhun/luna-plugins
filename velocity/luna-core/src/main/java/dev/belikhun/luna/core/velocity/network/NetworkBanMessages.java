package dev.belikhun.luna.core.velocity.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words a refused or removed player reads, and the duration grammar the
 * moderation commands accept, kept in one place so a kick at pre-login and a
 * kick of someone already online say the same thing.
 */
public final class NetworkBanMessages {
	private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter
		.ofPattern("HH:mm dd/MM/yyyy")
		.withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

	/** One or more number-unit pairs: 30m, 12h, 7d, 2w, 1d12h. */
	private static final Pattern DURATION = Pattern.compile("^(?:\\d+[smhdwy])+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdwy])", Pattern.CASE_INSENSITIVE);

	private NetworkBanMessages() {
	}

	/** Shown to a banned account, at login or when the ban lands while online. */
	public static Component playerBanned(String reason, long expiresAt) {
		return withTerms(
			Component.text("Bạn đã bị cấm khỏi mạng máy chủ.", NamedTextColor.RED),
			reason,
			expiresAt
		);
	}

	/** Shown to every connection from a banned address. */
	public static Component ipBanned(String reason, long expiresAt) {
		return withTerms(
			Component.text("Địa chỉ IP của bạn đã bị cấm khỏi máy chủ.", NamedTextColor.RED),
			reason,
			expiresAt
		);
	}

	/** Shown to a kicked player when the kick gives no reason of its own. */
	public static Component kicked(String reason) {
		Component message = Component.text("Bạn đã bị ngắt kết nối bởi quản trị viên.", NamedTextColor.RED);

		if (reason == null || reason.isBlank()) {
			return message;
		}

		return message.append(Component.newline())
			.append(Component.text("Lý do: " + reason, NamedTextColor.GRAY));
	}

	/** When a ban ends, in the network's own time zone, or "vĩnh viễn". */
	public static String expiryText(long expiresAt) {
		if (expiresAt <= 0) {
			return "vĩnh viễn";
		}

		return EXPIRY_FORMAT.format(Instant.ofEpochMilli(expiresAt));
	}

	/** Whether a word reads as a ban length rather than the first word of a reason. */
	public static boolean isDuration(String word) {
		if (word == null) {
			return false;
		}

		String trimmed = word.trim().toLowerCase(Locale.ROOT);

		if (trimmed.equals("perm") || trimmed.equals("permanent") || trimmed.equals("vinhvien")) {
			return true;
		}

		return DURATION.matcher(trimmed).matches();
	}

	/**
	 * A ban length in milliseconds: 0 for permanent, -1 when the text is not a
	 * duration at all.
	 */
	public static long parseDuration(String word) {
		if (!isDuration(word)) {
			return -1L;
		}

		String trimmed = word.trim().toLowerCase(Locale.ROOT);

		if (!Character.isDigit(trimmed.charAt(0))) {
			return 0L;
		}

		long total = 0L;
		Matcher matcher = DURATION_PART.matcher(trimmed);

		while (matcher.find()) {
			long amount = Long.parseLong(matcher.group(1));
			long unit = switch (matcher.group(2)) {
				case "s" -> 1_000L;
				case "m" -> 60_000L;
				case "h" -> 3_600_000L;
				case "d" -> 86_400_000L;
				case "w" -> 604_800_000L;
				default -> 31_536_000_000L;
			};

			total += amount * unit;
		}

		return total;
	}

	private static Component withTerms(Component headline, String reason, long expiresAt) {
		Component message = headline;

		if (reason != null && !reason.isBlank()) {
			message = message.append(Component.newline())
				.append(Component.text("Lý do: " + reason, NamedTextColor.GRAY));
		}

		return message.append(Component.newline())
			.append(Component.text("Hết hạn: " + expiryText(expiresAt), NamedTextColor.GRAY));
	}
}
