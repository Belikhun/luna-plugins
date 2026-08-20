package dev.belikhun.luna.legacy.messenger.runtime;

import dev.belikhun.luna.legacy.dependency.DependencyManager;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.legacy.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.legacy.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.legacy.placeholder.PlaceholderService;
import dev.belikhun.luna.legacy.string.Strings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoreBackedPlaceholderResolver<P> implements BackendPlaceholderResolver {
	private static final int MAX_DISCOVERY_ROUNDS = 5;
	private static final int MAX_NESTED_RESOLVE_PASSES = 5;
	private static final Pattern TOKEN_PATTERN = Pattern.compile("%([^%\\s]+)%");
	private static final List<String> DEFAULT_EXPORT_KEYS = Collections.unmodifiableList(Arrays.asList(
		"luckperms_prefix",
		"luckperms_suffix",
		"luckperms_primary_group_name",
		"vault_prefix",
		"vault_suffix",
		"vault_primary_group",
		"player_displayname",
		// the chat formats use it and only a backend can answer it: the proxy has no
		// idea which dimension a player is standing in
		"luna_player_dimension"
	));

	private final DependencyManager dependencyManager;
	private final PlayerBridge<P> players;
	private final Set<String> runtimeDiscoveredKeys;

	CoreBackedPlaceholderResolver(DependencyManager dependencyManager, PlayerBridge<P> players) {
		this.dependencyManager = dependencyManager;
		this.players = players;
		this.runtimeDiscoveredKeys = ConcurrentHashMap.newKeySet();
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		if (request == null) {
			return new PlaceholderResolutionResult("", Collections.<String, String>emptyMap());
		}

		Map<String, String> exported = new LinkedHashMap<>(request.internalValues());
		exported.putIfAbsent("sender_name", request.playerName());
		exported.putIfAbsent("player_name", request.playerName());
		exported.putIfAbsent("sender_server", request.sourceServer());
		exported.putIfAbsent("server_name", request.sourceServer());
		exported.putIfAbsent("player_uuid", request.playerId().toString());

		String resolvedContent = applyInternal(request.content(), exported);
		PlaceholderService placeholderService = resolvePlaceholderService();
		P player = resolvePlayer(request.playerId());
		if (placeholderService == null || player == null) {
			return new PlaceholderResolutionResult(resolvedContent, exported);
		}

		LinkedHashSet<String> tokensToResolve = new LinkedHashSet<>(DEFAULT_EXPORT_KEYS);
		tokensToResolve.addAll(runtimeDiscoveredKeys);
		tokensToResolve.addAll(extractTokens(request.content()));
		tokensToResolve.addAll(extractTokens(resolvedContent));

		for (int round = 0; round < MAX_DISCOVERY_ROUNDS; round++) {
			int sizeBefore = tokensToResolve.size();
			String previousContent = resolvedContent;
			resolvedContent = resolveNestedWithService(placeholderService, player, resolvedContent);
			tokensToResolve.addAll(extractTokens(resolvedContent));

			for (String token : new ArrayList<String>(tokensToResolve)) {
				String resolvedValue = resolveTokenWithService(placeholderService, player, token);
				exported.put(token, resolvedValue);
				tokensToResolve.addAll(extractTokens(resolvedValue));
			}

			if (tokensToResolve.size() == sizeBefore && resolvedContent.equals(previousContent)) {
				break;
			}
		}

		runtimeDiscoveredKeys.addAll(tokensToResolve);
		return new PlaceholderResolutionResult(resolvedContent, exported);
	}

	private PlaceholderService resolvePlaceholderService() {
		return dependencyManager == null
			? null
			: (PlaceholderService<P>) dependencyManager.find(PlaceholderService.class);
	}

	private P resolvePlayer(java.util.UUID playerId) {
		if (playerId == null || players == null) {
			return null;
		}

		return players.byId(playerId);
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

	private String resolveNestedWithService(PlaceholderService placeholderService, P player, String text) {
		String current = text == null ? "" : text;
		for (int i = 0; i < MAX_NESTED_RESOLVE_PASSES; i++) {
			String next = resolveTokensOnce(placeholderService, player, current);
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

	private String resolveTokensOnce(PlaceholderService placeholderService, P player, String text) {
		if (Strings.isBlank(text)) {
			return text == null ? "" : text;
		}

		Matcher matcher = TOKEN_PATTERN.matcher(text);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find()) {
			String token = matcher.group(1);
			String replacement = resolveTokenWithService(placeholderService, player, token);
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private String resolveTokenWithService(PlaceholderService placeholderService, P player, String token) {
		String current = "%" + token + "%";
		for (int i = 0; i < MAX_NESTED_RESOLVE_PASSES; i++) {
			String resolved = placeholderService.resolvePlaceholder(player, current);
			if (resolved == null || resolved.equals(current)) {
				break;
			}
			current = resolved;
			if (extractTokens(current).isEmpty()) {
				break;
			}
		}
		return current;
	}

	private List<String> extractTokens(String text) {
		if (Strings.isBlank(text)) {
			return Collections.<String>emptyList();
		}

		Matcher matcher = TOKEN_PATTERN.matcher(text);
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group(1));
		}
		return Collections.unmodifiableList(new ArrayList<String>(tokens));
	}
}
