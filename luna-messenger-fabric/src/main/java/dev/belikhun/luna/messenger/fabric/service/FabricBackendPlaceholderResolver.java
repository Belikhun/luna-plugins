package dev.belikhun.luna.messenger.fabric.service;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
		this(PlaceholderBridge.autoDetect(), List.of());
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
			return ReflectionPlaceholderBridge.detect();
		}
	}

	private static final class ReflectionPlaceholderBridge implements PlaceholderBridge {
		private static final String[] PLACEHOLDER_API_CANDIDATES = new String[] {
			"eu.pb4.placeholders.api.PlaceholderAPI",
			"eu.pb4.placeholders.api.Placeholders"
		};

		private final Method resolveMethod;
		private final InvokeMode invokeMode;

		private ReflectionPlaceholderBridge(Method resolveMethod, InvokeMode invokeMode) {
			this.resolveMethod = resolveMethod;
			this.invokeMode = invokeMode;
		}

		static PlaceholderBridge detect() {
			for (String className : PLACEHOLDER_API_CANDIDATES) {
				try {
					Class<?> type = Class.forName(className, false, FabricBackendPlaceholderResolver.class.getClassLoader());
					for (Method method : type.getMethods()) {
						if (!Modifier.isStatic(method.getModifiers())) {
							continue;
						}
						if (method.getReturnType() != String.class) {
							continue;
						}

						Class<?>[] parameterTypes = method.getParameterTypes();
						if (parameterTypes.length == 1 && parameterTypes[0] == String.class) {
							return new ReflectionPlaceholderBridge(method, InvokeMode.SINGLE_STRING);
						}
						if (parameterTypes.length == 2 && parameterTypes[0] == String.class) {
							return new ReflectionPlaceholderBridge(method, InvokeMode.STRING_CONTEXT);
						}
						if (parameterTypes.length == 2 && parameterTypes[1] == String.class) {
							return new ReflectionPlaceholderBridge(method, InvokeMode.CONTEXT_STRING);
						}
					}
				} catch (ClassNotFoundException ignored) {
					// Ignore and try next candidate.
				}
			}

			return PlaceholderBridge.noop();
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public String resolveText(UUID playerId, String text) {
			String safeText = text == null ? "" : text;
			try {
				Object value = switch (invokeMode) {
					case SINGLE_STRING -> resolveMethod.invoke(null, safeText);
					case STRING_CONTEXT -> resolveMethod.invoke(null, safeText, null);
					case CONTEXT_STRING -> resolveMethod.invoke(null, null, safeText);
				};

				if (value instanceof String resolved) {
					return resolved;
				}
			} catch (ReflectiveOperationException ignored) {
				return safeText;
			}

			return safeText;
		}

		enum InvokeMode {
			SINGLE_STRING,
			STRING_CONTEXT,
			CONTEXT_STRING
		}
	}
}
