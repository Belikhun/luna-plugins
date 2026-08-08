package dev.belikhun.luna.messenger.velocity.service;

import com.velocitypowered.api.proxy.Player;
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

	private volatile boolean enabled = true;

	public String resolve(Player player, String template) {
		return resolve(player, template, Set.of());
	}

	public String resolve(Player player, String template, Set<String> preservedPlaceholders) {
		if (!enabled || template == null || template.isBlank()) {
			return template == null ? "" : template;
		}

		try {
			String normalizedTemplate = rewriteLegacyPercentPlaceholders(template, preservedPlaceholders);
			TagResolver global = MiniPlaceholders.globalPlaceholders();
			TagResolver audience = MiniPlaceholders.audiencePlaceholders();

			Component component = player == null
				? MM.deserialize(normalizedTemplate, global, audience)
				: MM.deserialize(normalizedTemplate, player, global, audience);
			return MM.serialize(component);
		} catch (NoClassDefFoundError | Exception ignored) {
			// If MiniPlaceholders is missing or errors unexpectedly, disable further attempts.
			enabled = false;
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
		if (mappedNamespace != null) {
			return "<" + mappedNamespace + ":" + key + ">";
		}

		return "<" + token + ">";
	}
}
