package dev.belikhun.luna.messenger.fabric.placeholder;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.core.api.profile.PermissionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a backend fills in before a message leaves for the proxy.
 *
 * The proxy renders the final line, so this side's job is to export the values
 * only the backend knows: who sent it, from where, and what rank they hold here.
 * Both spellings are substituted in the content itself, {@code {key}} and
 * {@code %key%}, because operators write templates in either.
 *
 * The rank keys are read straight from the core's {@link PermissionService}
 * rather than through a placeholder engine. Fabric has no port of the NeoForge
 * placeholder service yet, so a message carrying a placeholder outside this set
 * reaches the proxy unexpanded.
 */
public final class BackendPlaceholders implements BackendPlaceholderResolver {
	private final DependencyManager dependencyManager;

	public BackendPlaceholders(DependencyManager dependencyManager) {
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
		exported.putIfAbsent("player_displayname", request.playerName());

		exportRankValues(exported, request.playerId());

		return new PlaceholderResolutionResult(substitute(request.content(), exported), exported);
	}

	/**
	 * The rank values under both the luckperms and the vault spelling: a proxy-side
	 * template written against either one resolves the same on this backend, and
	 * Fabric has no Vault to disagree with.
	 */
	private void exportRankValues(Map<String, String> exported, UUID playerId) {
		PermissionService permissionService = permissionService();

		if (permissionService == null || !permissionService.isAvailable()) {
			return;
		}

		String prefix = safe(permissionService.getPlayerPrefix(playerId));
		String suffix = safe(permissionService.getPlayerSuffix(playerId));
		String groupName = safe(permissionService.getGroupName(playerId));

		exported.put("luckperms_prefix", prefix);
		exported.put("luckperms_suffix", suffix);
		exported.put("luckperms_primary_group_name", groupName);
		exported.put("luckperms_primary_group_display_name", safe(permissionService.getGroupDisplayName(playerId)));
		exported.put("vault_prefix", prefix);
		exported.put("vault_suffix", suffix);
		exported.put("vault_primary_group", groupName);
	}

	private PermissionService permissionService() {
		if (dependencyManager == null) {
			return null;
		}

		return dependencyManager.resolveOptional(PermissionService.class).orElse(null);
	}

	private String substitute(String content, Map<String, String> values) {
		String output = content == null ? "" : content;

		for (Map.Entry<String, String> entry : values.entrySet()) {
			String value = safe(entry.getValue());

			output = output.replace("{" + entry.getKey() + "}", value);
			output = output.replace("%" + entry.getKey() + "%", value);
		}

		return output;
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
