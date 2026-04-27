package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.core.neoforge.placeholder.NeoForgePlaceholderService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoreBackedNeoForgePlaceholderResolver implements BackendPlaceholderResolver {
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

	private final DependencyManager dependencyManager;
	private final Set<String> runtimeDiscoveredKeys;

	CoreBackedNeoForgePlaceholderResolver(DependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
		this.runtimeDiscoveredKeys = ConcurrentHashMap.newKeySet();
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

		String resolvedContent = applyInternal(request.content(), exported);
		NeoForgePlaceholderService placeholderService = resolvePlaceholderService();
		ServerPlayer player = resolvePlayer(request.playerId());
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

			for (String token : List.copyOf(tokensToResolve)) {
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

	private NeoForgePlaceholderService resolvePlaceholderService() {
		return dependencyManager == null
			? null
			: dependencyManager.resolveOptional(NeoForgePlaceholderService.class).orElse(null);
	}

	private ServerPlayer resolvePlayer(java.util.UUID playerId) {
		if (playerId == null || dependencyManager == null) {
			return null;
		}

		MinecraftServer server = dependencyManager.resolveOptional(MinecraftServer.class).orElse(null);
		if (server == null || server.getPlayerList() == null) {
			return null;
		}

		return server.getPlayerList().getPlayer(playerId);
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

	private String resolveNestedWithService(NeoForgePlaceholderService placeholderService, ServerPlayer player, String text) {
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

	private String resolveTokensOnce(NeoForgePlaceholderService placeholderService, ServerPlayer player, String text) {
		if (text == null || text.isBlank()) {
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

	private String resolveTokenWithService(NeoForgePlaceholderService placeholderService, ServerPlayer player, String token) {
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
		if (text == null || text.isBlank()) {
			return List.of();
		}

		Matcher matcher = TOKEN_PATTERN.matcher(text);
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group(1));
		}
		return List.copyOf(tokens);
	}
}