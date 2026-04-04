package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FabricBackendPlaceholderResolver implements BackendPlaceholderResolver {
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

	private final PlaceholderBridge placeholderBridge;
	private final List<String> exportKeys;
	private final Set<String> runtimeDiscoveredKeys;

	public FabricBackendPlaceholderResolver() {
		this(() -> null);
	}

	public FabricBackendPlaceholderResolver(Supplier<MinecraftServer> serverSupplier) {
		this(PlaceholderBridge.placeholderApi(serverSupplier), List.of());
	}

	FabricBackendPlaceholderResolver(PlaceholderBridge placeholderBridge, List<String> configuredExportKeys) {
		this.placeholderBridge = placeholderBridge == null ? PlaceholderBridge.noop() : placeholderBridge;
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

		String resolvedContent = applyInternal(request.content(), exported);
		if (placeholderBridge.isAvailable()) {
			LinkedHashSet<String> tokensToResolve = new LinkedHashSet<>(exportKeys);
			tokensToResolve.addAll(runtimeDiscoveredKeys);
			tokensToResolve.addAll(extractTokens(request.content()));
			tokensToResolve.addAll(extractTokens(resolvedContent));

			for (int round = 0; round < MAX_DISCOVERY_ROUNDS; round++) {
				int sizeBefore = tokensToResolve.size();
				String nextContent = resolveNestedWithBridge(request.playerId(), resolvedContent);
				resolvedContent = nextContent;
				tokensToResolve.addAll(extractTokens(nextContent));

				for (String token : List.copyOf(tokensToResolve)) {
					String unresolvedToken = "%" + token + "%";
					String resolvedValue = resolveNestedWithBridge(request.playerId(), unresolvedToken);
					if (!Objects.equals(resolvedValue, unresolvedToken)) {
						exported.put(token, resolvedValue);
						tokensToResolve.addAll(extractTokens(resolvedValue));
					}
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
		LinkedHashSet<String> merged = new LinkedHashSet<>(DEFAULT_EXPORT_KEYS);
		if (configuredExportKeys != null) {
			for (String key : configuredExportKeys) {
				if (key == null) {
					continue;
				}

				String normalized = key.trim();
				if (!normalized.isEmpty()) {
					merged.add(normalized);
				}
			}
		}
		return merged.stream().filter(Objects::nonNull).toList();
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

	private String resolveNestedWithBridge(UUID playerId, String text) {
		String current = text == null ? "" : text;
		for (int i = 0; i < MAX_NESTED_RESOLVE_PASSES; i++) {
			String next = placeholderBridge.resolveText(playerId, current);
			if (Objects.equals(next, current)) {
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
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group(1));
		}

		return List.copyOf(tokens);
	}

	interface PlaceholderBridge {
		boolean isAvailable();

		String resolveText(UUID playerId, String text);

		static PlaceholderBridge noop() {
			return new PlaceholderBridge() {
				@Override
				public boolean isAvailable() {
					return false;
				}

				@Override
				public String resolveText(UUID playerId, String text) {
					return text == null ? "" : text;
				}
			};
		}

		static PlaceholderBridge autoDetect() {
			return noop();
		}

		static PlaceholderBridge placeholderApi(Supplier<MinecraftServer> serverSupplier) {
			return new PlaceholderApiBridge(serverSupplier);
		}
	}

	private static final class PlaceholderApiBridge implements PlaceholderBridge {
		private final Supplier<MinecraftServer> serverSupplier;

		private PlaceholderApiBridge(Supplier<MinecraftServer> serverSupplier) {
			this.serverSupplier = serverSupplier == null ? () -> null : serverSupplier;
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public String resolveText(UUID playerId, String text) {
			String safeText = text == null ? "" : text;
			MinecraftServer server = serverSupplier.get();
			if (server == null) {
				return safeText;
			}

			ServerPlayer player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
			PlaceholderContext context = player != null ? PlaceholderContext.of(player) : PlaceholderContext.of(server);
			Component resolved = Placeholders.parseText(
				Component.literal(safeText),
				context,
				Placeholders.PLACEHOLDER_PATTERN_CUSTOM
			);
			return resolved.getString();
		}
	}
}
