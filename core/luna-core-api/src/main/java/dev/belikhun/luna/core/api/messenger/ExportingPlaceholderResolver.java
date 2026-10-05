package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.placeholder.PlaceholderSyntax;
import dev.belikhun.luna.core.api.placeholder.PlaceholderTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The one backend-side placeholder exporter, for every platform.
 *
 * A message leaves the backend with the values the proxy's templates need but
 * cannot compute: prefixes, groups, the dimension the player stands in. Those
 * are a fixed list, defaults plus configuration. A token typed into chat is
 * never added to that list, which is what the old runtime discovery did, and
 * the export is capped so one message cannot carry an unbounded map.
 *
 * The platform supplies one function: resolve an identifier written as
 * {@code %ns_key%} for a player, or null when nothing answers.
 */
public final class ExportingPlaceholderResolver implements BackendPlaceholderResolver {
	/** The keys every chat format on the proxy has needed so far. */
	public static final List<String> DEFAULT_EXPORT_KEYS = List.of(
		"luckperms_prefix",
		"luckperms_suffix",
		"luckperms_primary_group_name",
		"vault_prefix",
		"vault_suffix",
		"vault_primary_group",
		"player_displayname",
		// only a backend knows the dimension a player is standing in
		"luna_player_dimension"
	);
	private static final int MAX_EXPORTED_VALUES = 64;
	private static final int MAX_VALUE_LENGTH = 1024;

	private final List<String> exportKeys;
	private final BiFunction<UUID, String, String> resolver;

	/**
	 * @param configuredExportKeys keys from the backend's config, merged with the defaults
	 * @param resolver             resolves {@code %ns_key%} for a player id, null when unanswered
	 */
	public ExportingPlaceholderResolver(Collection<String> configuredExportKeys, BiFunction<UUID, String, String> resolver) {
		LinkedHashSet<String> merged = new LinkedHashSet<>(DEFAULT_EXPORT_KEYS);

		if (configuredExportKeys != null) {
			for (String key : configuredExportKeys) {
				if (key != null && !key.isBlank()) {
					merged.add(key.trim().replace("%", ""));
				}
			}
		}

		this.exportKeys = List.copyOf(new ArrayList<>(merged));
		this.resolver = resolver;
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		if (request == null) {
			return new PlaceholderResolutionResult("", Map.of());
		}

		Map<String, String> exported = new LinkedHashMap<>(request.internalValues());
		exported.putIfAbsent("sender_name", request.playerName());
		exported.putIfAbsent("player_name", request.playerName());
		exported.putIfAbsent("sender_server", request.sourceServer());
		exported.putIfAbsent("server_name", request.sourceServer());
		exported.putIfAbsent("player_uuid", request.playerId().toString());

		for (String key : exportKeys) {
			if (exported.size() >= MAX_EXPORTED_VALUES) {
				break;
			}

			String value = resolver == null ? null : resolver.apply(request.playerId(), "%" + key + "%");

			if (value != null) {
				exported.put(key, value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value);
			}
		}

		// the message body only sees the exported values; nothing else in it is expanded
		String content = PlaceholderTemplate.parse(request.content(), EnumSet.of(PlaceholderSyntax.PERCENT, PlaceholderSyntax.BRACE))
			.renderValues(exported);

		return new PlaceholderResolutionResult(content, exported);
	}
}
