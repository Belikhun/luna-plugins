package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.tabbridge.neoforge.runtime.NeoForgeTabBridgeRuntime;

import java.util.LinkedHashMap;
import java.util.Map;

final class TabBridgeSnapshotPlaceholderResolver implements BackendPlaceholderResolver {
	private final DependencyManager dependencyManager;

	TabBridgeSnapshotPlaceholderResolver(DependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
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

		NeoForgeTabBridgeRuntime tabBridgeRuntime = dependencyManager == null
			? null
			: dependencyManager.resolveOptional(NeoForgeTabBridgeRuntime.class).orElse(null);
		if (tabBridgeRuntime != null) {
			for (Map.Entry<String, String> entry : tabBridgeRuntime.placeholderValues(request.playerId()).entrySet()) {
				String key = entry.getKey();
				if (key == null || key.isBlank()) {
					continue;
				}

				exported.put(key, entry.getValue() == null ? "" : entry.getValue());
			}
		}

		return new PlaceholderResolutionResult(applyValues(request.content(), exported), exported);
	}

	private String applyValues(String content, Map<String, String> values) {
		String resolved = content == null ? "" : content;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}

			String value = entry.getValue() == null ? "" : entry.getValue();
			resolved = resolved.replace("{" + key + "}", value);
			resolved = resolved.replace("%" + key + "%", value);
		}
		return resolved;
	}
}
