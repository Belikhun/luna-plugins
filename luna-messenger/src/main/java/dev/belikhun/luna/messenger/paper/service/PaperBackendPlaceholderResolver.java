package dev.belikhun.luna.messenger.paper.service;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperBackendPlaceholderResolver implements BackendPlaceholderResolver {
	private static final int MAX_DISCOVERY_ROUNDS = 5;
	private static final int MAX_NESTED_RESOLVE_PASSES = 5;
	private static final Pattern TOKEN_PATTERN = Pattern.compile("%([a-zA-Z0-9_:.\\-]+)%");
	private static final List<String> DEFAULT_EXPORT_KEYS = List.of(
		"luckperms_prefix",
		"luckperms_suffix",
		"luckperms_primary_group_name",
		"vault_prefix",
		"vault_suffix",
		"vault_primary_group",
		"player_displayname"
	);

	private final JavaPlugin plugin;
	private final List<String> exportKeys;
	private final Set<String> runtimeDiscoveredKeys;

	public PaperBackendPlaceholderResolver(JavaPlugin plugin, List<String> configuredExportKeys) {
		this.plugin = plugin;
		this.exportKeys = sanitizeExportKeys(configuredExportKeys);
		this.runtimeDiscoveredKeys = ConcurrentHashMap.newKeySet();
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		Map<String, String> exported = new LinkedHashMap<>(request.internalValues());
		exported.putIfAbsent("sender_name", request.playerName());
		exported.putIfAbsent("player_name", request.playerName());
		exported.putIfAbsent("sender_server", request.sourceServer());
		exported.putIfAbsent("server_name", request.sourceServer());
		exported.putIfAbsent("player_uuid", request.playerId().toString());

		Player player = plugin.getServer().getPlayer(request.playerId());
		String resolvedContent = applyInternal(request.content(), exported);
		if (player != null && isPlaceholderApiAvailable()) {
			LinkedHashSet<String> tokensToResolve = new LinkedHashSet<>(exportKeys);
			tokensToResolve.addAll(runtimeDiscoveredKeys);
			tokensToResolve.addAll(extractTokens(request.content()));
			tokensToResolve.addAll(extractTokens(resolvedContent));

			for (int round = 0; round < MAX_DISCOVERY_ROUNDS; round++) {
				int sizeBefore = tokensToResolve.size();
				String nextContent = resolveNestedWithPlaceholderApi(player, resolvedContent);
				resolvedContent = nextContent;
				tokensToResolve.addAll(extractTokens(nextContent));

				for (String token : List.copyOf(tokensToResolve)) {
					String resolvedValue = resolveNestedWithPlaceholderApi(player, "%" + token + "%");
					exported.put(token, resolvedValue);
					tokensToResolve.addAll(extractTokens(resolvedValue));
				}

				if (tokensToResolve.size() == sizeBefore) {
					break;
				}
			}

			runtimeDiscoveredKeys.addAll(tokensToResolve);
		}

		return new PlaceholderResolutionResult(resolvedContent, exported);
	}

	private List<String> sanitizeExportKeys(List<String> configuredExportKeys) {
		java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(DEFAULT_EXPORT_KEYS);
		if (configuredExportKeys != null) {
			for (String key : configuredExportKeys) {
				if (key == null) {
					continue;
				}
				String normalized = key.trim();
				if (normalized.isEmpty()) {
					continue;
				}
				merged.add(normalized);
			}
		}
		return merged.stream().filter(Objects::nonNull).toList();
	}

	private boolean isPlaceholderApiAvailable() {
		return plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
	}

	private String applyInternal(String content, Map<String, String> values) {
		String output = content == null ? "" : content;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue() == null ? "" : entry.getValue();
			output = output.replace("{" + key + "}", value);
			output = output.replace("%" + key + "%", value);
		}
		return output;
	}

	private String resolveWithPlaceholderApi(Player player, String text) {
		if (!isPlaceholderApiAvailable()) {
			return text == null ? "" : text;
		}

		try {
			return PlaceholderAPI.setPlaceholders(player, text == null ? "" : text);
		} catch (Exception exception) {
			return text == null ? "" : text;
		}
	}

	private String resolveNestedWithPlaceholderApi(Player player, String text) {
		String current = text == null ? "" : text;
		for (int i = 0; i < MAX_NESTED_RESOLVE_PASSES; i++) {
			String next = resolveWithPlaceholderApi(player, current);
			if (next.equals(current)) {
				break;
			}
			current = next;
			if (extractTokens(current).isEmpty()) {
				break;
			}
		}
		return current;
	}

	private List<String> extractTokens(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}

		Matcher matcher = TOKEN_PATTERN.matcher(text);
		java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group(1));
		}
		return List.copyOf(tokens);
	}
}
