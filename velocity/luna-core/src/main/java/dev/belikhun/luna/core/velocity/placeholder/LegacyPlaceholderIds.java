package dev.belikhun.luna.core.velocity.placeholder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The proxy placeholder ids that predate the {@code lunav} namespace, and what
 * each one is called now.
 *
 * One table serves three purposes: the TAB adapter registers the old ids as
 * aliases for one release, the startup scan names the config lines still using
 * them, and the migrate command rewrites those lines.
 */
public final class LegacyPlaceholderIds {
	private static final Pattern VAULT = Pattern.compile("%lunavaultv-([a-z_]+)%");
	private static final Pattern GLYPH = Pattern.compile("%lunaglyph-([^%\\s]+)%");
	private static final Pattern HYPHENATED = Pattern.compile("%lunav-([^%\\s]+)%");
	private static final Pattern PROXY_LUNA_TAG = Pattern.compile("<luna_(player_status|player_name|player_display|player_prefix|player_suffix|player_group_name|player_group_display|online_servers|registered_servers|total_servers|total_players|server_[a-z_]+)([:>])");

	private LegacyPlaceholderIds() {
	}

	/**
	 * Every legacy id in the text with its replacement, in order of appearance.
	 *
	 * @param text a config file's contents
	 * @return legacy id to canonical id, empty when the text is clean
	 */
	public static Map<String, String> findLegacy(String text) {
		Map<String, String> found = new LinkedHashMap<>();

		if (text == null || text.isEmpty()) {
			return found;
		}

		collect(found, VAULT.matcher(text), matcher -> "%lunav_vault_" + matcher.group(1).toLowerCase(Locale.ROOT) + "%");
		collect(found, GLYPH.matcher(text), matcher -> "%lunav_glyph_" + matcher.group(1).toLowerCase(Locale.ROOT) + "%");
		collect(found, HYPHENATED.matcher(text), matcher -> "%lunav_" + matcher.group(1).replace('-', '_') + "%");
		collect(found, PROXY_LUNA_TAG.matcher(text), matcher -> "<lunav_" + matcher.group(1) + matcher.group(2));

		return found;
	}

	/**
	 * The text with every legacy id replaced by its canonical form.
	 *
	 * @param text a config file's contents
	 * @return the rewritten text, identical when nothing was legacy
	 */
	public static String rewrite(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		String output = text;

		for (Map.Entry<String, String> entry : findLegacy(text).entrySet()) {
			output = output.replace(entry.getKey(), entry.getValue());
		}

		return output;
	}

	/** The canonical id a legacy TAB id stands for, or null when the id is not legacy. */
	public static String canonicalTabId(String legacyId) {
		Map<String, String> found = findLegacy(legacyId);
		return found.isEmpty() ? null : found.values().iterator().next();
	}

	private interface Replacement {
		String of(Matcher matcher);
	}

	private static void collect(Map<String, String> found, Matcher matcher, Replacement replacement) {
		while (matcher.find()) {
			found.putIfAbsent(matcher.group(), replacement.of(matcher));
		}
	}
}
