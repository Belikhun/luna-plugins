package dev.belikhun.luna.core.velocity.network.firewall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The firewall's switches and thresholds, stored as key/value rows so a new
 * setting needs no schema change. Every detector is off until an operator
 * turns it on: a detector that bans is a decision, not a default.
 *
 * @param enabled             rules and detectors run at all (bans are enforced either way)
 * @param loginFlood          ban an address opening more than {@code loginFloodPerMinute} logins a minute
 * @param pingScan            ban an address that pings the server list more than {@code pingScanCount}
 *                            times in {@code pingScanMinutes} without ever logging in
 * @param suspiciousNames     ban an address logging in under a name matching {@code namePatterns}
 * @param failedLogins        ban an address failing the password more than {@code failedLoginCount}
 *                            times in {@code failedLoginMinutes}, across accounts
 * @param hidePingFromRefused answer a refused address's server-list ping with an empty, offline-looking entry
 * @param banMinutes          an automatic ban's first length
 * @param repeatMultiplier    each repeat ban is this many times the last
 * @param maxBanDays          the longest an automatic ban gets
 * @param protect             addresses and prefixes never banned automatically nor refused by a rule
 */
public record FirewallSettings(
	boolean enabled,
	boolean loginFlood,
	int loginFloodPerMinute,
	boolean pingScan,
	int pingScanCount,
	int pingScanMinutes,
	boolean suspiciousNames,
	List<String> namePatterns,
	boolean failedLogins,
	int failedLoginCount,
	int failedLoginMinutes,
	boolean hidePingFromRefused,
	int banMinutes,
	double repeatMultiplier,
	int maxBanDays,
	List<String> protect
) {
	/** Loopback, private, CGNAT and link-local ranges: never refused and never banned. */
	public static final List<String> PROTECTED_RANGES = List.of(
		"127.0.0.0/8",
		"10.0.0.0/8",
		"172.16.0.0/12",
		"192.168.0.0/16",
		"100.64.0.0/10",
		"169.254.0.0/16",
		"::1/128",
		"fc00::/7",
		"fe80::/10"
	);

	public static FirewallSettings defaults() {
		return new FirewallSettings(
			true,
			false, 20,
			false, 30, 10,
			false, List.of(),
			false, 8, 10,
			true,
			60, 4.0, 30,
			List.of()
		);
	}

	/** Read the stored rows over the defaults; a value that does not parse keeps its default. */
	public static FirewallSettings fromMap(Map<String, String> values) {
		FirewallSettings base = defaults();

		return new FirewallSettings(
			bool(values, "enabled", base.enabled()),
			bool(values, "loginFlood", base.loginFlood()),
			integer(values, "loginFloodPerMinute", base.loginFloodPerMinute(), 2, 10_000),
			bool(values, "pingScan", base.pingScan()),
			integer(values, "pingScanCount", base.pingScanCount(), 3, 100_000),
			integer(values, "pingScanMinutes", base.pingScanMinutes(), 1, 1440),
			bool(values, "suspiciousNames", base.suspiciousNames()),
			lines(values, "namePatterns", base.namePatterns()),
			bool(values, "failedLogins", base.failedLogins()),
			integer(values, "failedLoginCount", base.failedLoginCount(), 2, 10_000),
			integer(values, "failedLoginMinutes", base.failedLoginMinutes(), 1, 1440),
			bool(values, "hidePingFromRefused", base.hidePingFromRefused()),
			integer(values, "banMinutes", base.banMinutes(), 1, 525_600),
			decimal(values, "repeatMultiplier", base.repeatMultiplier(), 1.0, 100.0),
			integer(values, "maxBanDays", base.maxBanDays(), 1, 3650),
			lines(values, "protect", base.protect())
		);
	}

	/** The rows to store. */
	public Map<String, String> toMap() {
		Map<String, String> out = new LinkedHashMap<>();

		out.put("enabled", String.valueOf(enabled));
		out.put("loginFlood", String.valueOf(loginFlood));
		out.put("loginFloodPerMinute", String.valueOf(loginFloodPerMinute));
		out.put("pingScan", String.valueOf(pingScan));
		out.put("pingScanCount", String.valueOf(pingScanCount));
		out.put("pingScanMinutes", String.valueOf(pingScanMinutes));
		out.put("suspiciousNames", String.valueOf(suspiciousNames));
		out.put("namePatterns", String.join("\n", namePatterns));
		out.put("failedLogins", String.valueOf(failedLogins));
		out.put("failedLoginCount", String.valueOf(failedLoginCount));
		out.put("failedLoginMinutes", String.valueOf(failedLoginMinutes));
		out.put("hidePingFromRefused", String.valueOf(hidePingFromRefused));
		out.put("banMinutes", String.valueOf(banMinutes));
		out.put("repeatMultiplier", String.valueOf(repeatMultiplier));
		out.put("maxBanDays", String.valueOf(maxBanDays));
		out.put("protect", String.join("\n", protect));

		return out;
	}

	/** The name patterns that compile; a broken one is skipped rather than failing every login. */
	public List<Pattern> compiledNamePatterns() {
		List<Pattern> out = new ArrayList<>();

		for (String text : namePatterns) {
			try {
				out.add(Pattern.compile(text, Pattern.CASE_INSENSITIVE));
			} catch (PatternSyntaxException exception) {
				// reported by the console's validation; enforcement just skips it
			}
		}

		return out;
	}

	/** How long the {@code strike}th automatic ban lasts, milliseconds. */
	public long banLengthMillis(int strike) {
		double minutes = banMinutes * Math.pow(repeatMultiplier, Math.max(0, strike - 1));
		double capped = Math.min(minutes, maxBanDays * 24.0 * 60.0);

		return Math.round(capped * 60_000.0);
	}

	private static boolean bool(Map<String, String> values, String key, boolean fallback) {
		String value = values.get(key);

		return value == null
			? fallback
			: "true".equalsIgnoreCase(value.trim());
	}

	private static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
		try {
			int value = Integer.parseInt(values.getOrDefault(key, "").trim());

			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static double decimal(Map<String, String> values, String key, double fallback, double min, double max) {
		try {
			double value = Double.parseDouble(values.getOrDefault(key, "").trim());

			return Double.isFinite(value)
				? Math.max(min, Math.min(max, value))
				: fallback;
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static List<String> lines(Map<String, String> values, String key, List<String> fallback) {
		String value = values.get(key);

		if (value == null) {
			return fallback;
		}

		List<String> out = new ArrayList<>();

		for (String line : value.split("\n")) {
			String trimmed = line.trim();

			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}

		return out;
	}
}
