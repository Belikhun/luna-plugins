package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VelocityMiniPlaceholderResolver {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final Pattern LEGACY_PERCENT_PATTERN = Pattern.compile("%([^%\\s]+)%");
	private static final Map<String, String> NAMESPACE_ALIASES = Map.of(
		"luna", "luna"
	);

	private final LunaLogger logger;

	private volatile boolean enabled = true;
	private volatile boolean parseFailureReported = false;

	public VelocityMiniPlaceholderResolver(LunaLogger logger) {
		this.logger = logger.scope("MiniPlaceholders");
	}

	public String resolve(Player player, String template) {
		return resolve(player, template, Set.of());
	}

	public String resolve(Player player, String template, Set<String> preservedPlaceholders) {
		if (!enabled || template == null || template.isBlank()) {
			return template == null ? "" : template;
		}

		String normalizedTemplate = rewriteLegacyPercentPlaceholders(template, preservedPlaceholders);

		try {
			TagResolver global = MiniPlaceholders.globalPlaceholders();
			TagResolver audience = MiniPlaceholders.audiencePlaceholders();

			Component component = player == null
				? MM.deserialize(normalizedTemplate, global, audience)
				: MM.deserialize(normalizedTemplate, player, global, audience);
			return MM.serialize(component);
		} catch (NoClassDefFoundError missing) {
			// MiniPlaceholders is not installed, so no later template can resolve either.
			enabled = false;
			logger.warn("Không tìm thấy MiniPlaceholders, các placeholder sẽ được giữ nguyên.");
			return template;
		} catch (Exception failure) {
			// A single malformed template must not disable resolution for the rest of the uptime:
			// that is how one bad format silently turns every luna placeholder into raw text.
			reportParseFailure(normalizedTemplate, failure);
			return template;
		}
	}

	private String rewriteLegacyPercentPlaceholders(String template, Set<String> preservedPlaceholders) {
		Set<String> preserved = preservedPlaceholders == null ? Set.of() : preservedPlaceholders;
		Matcher matcher = LEGACY_PERCENT_PATTERN.matcher(template);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String token = matcher.group(1);
			String replacement = toMiniPlaceholderTag(token, preserved);
			matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private String toMiniPlaceholderTag(String token, Set<String> preservedPlaceholders) {
		if (token == null || token.isBlank() || preservedPlaceholders.contains(token)) {
			return "%" + token + "%";
		}

		int separator = token.indexOf('_');
		if (separator <= 0 || separator >= token.length() - 1) {
			return "%" + token + "%";
		}

		String namespace = token.substring(0, separator);
		String key = token.substring(separator + 1);
		String mappedNamespace = NAMESPACE_ALIASES.getOrDefault(namespace, null);
		if (mappedNamespace == null) {
			return miniPlaceholderTag(token);
		}

		return miniPlaceholderTag(mappedNamespace + "_" + key);
	}

	/**
	 * Wraps a MiniPlaceholders placeholder name in the MiniMessage tag that addresses it.
	 *
	 * <p>An expansion registers its placeholders as {@code <namespace>_<name>}, so the tag name
	 * carries the namespace too; a {@code <namespace:name>} form matches nothing.
	 *
	 * <p>MiniMessage also restricts a tag name to {@code [a-zA-Z0-9_-]}, while PlaceholderAPI
	 * carries any suffix, so a name such as {@code luna_player_status_⏺} is never even offered to
	 * a resolver. A name with such a tail is split instead: the safe head stays the tag name and
	 * the tail becomes a quoted argument, which is how the Luna expansions read a flexible suffix.
	 *
	 * @param name the placeholder name, namespace included
	 * @return a MiniMessage tag, or the original percent form when no tag could address the name
	 */
	private String miniPlaceholderTag(String name) {
		int unsafe = firstUnsafeIndex(name);
		if (unsafe < 0) {
			return "<" + name + ">";
		}

		int split = name.lastIndexOf('_', unsafe);
		String head = split <= 0 ? "" : name.substring(0, split);
		if (head.indexOf('_') < 0) {
			// Only a namespace would be left, so there is no placeholder left to ask for.
			return "%" + name + "%";
		}

		return "<" + head + ":'" + name.substring(split + 1) + "'>";
	}

	private int firstUnsafeIndex(String name) {
		for (int index = 0; index < name.length(); index++) {
			if (!isTagNameSafe(name.charAt(index))) {
				return index;
			}
		}

		return -1;
	}

	private boolean isTagNameSafe(char character) {
		return (character >= 'a' && character <= 'z')
			|| (character >= 'A' && character <= 'Z')
			|| (character >= '0' && character <= '9')
			|| character == '_'
			|| character == '-';
	}

	private void reportParseFailure(String template, Exception failure) {
		if (parseFailureReported) {
			return;
		}

		// Reported once per resolver: a broken format would otherwise log on every message.
		parseFailureReported = true;
		logger.error("Không phân giải được MiniMessage cho template: " + template, failure);
	}
}
