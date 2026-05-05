package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

final class NeoForgePermissionPlaceholderProvider implements NeoForgePlaceholderProvider {
	private final PermissionService permissionService;

	NeoForgePermissionPlaceholderProvider(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	@Override
	public Set<String> namespaces() {
		return Set.of("luckperms", "vault");
	}

	@Override
	public String resolve(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		NeoForgePlaceholderSnapshot snapshot
	) {
		if (player == null || normalizedNamespace == null || normalizedNamespace.isBlank()) {
			return null;
		}

		if (permissionService == null || !permissionService.isAvailable()) {
			return null;
		}

		return switch (normalizedNamespace) {
			case "luckperms" -> resolveLuckPermsValue(support, player, normalizedParams);
			case "vault" -> resolveVaultValue(support, player, normalizedParams);
			default -> null;
		};
	}

	private String resolveLuckPermsValue(BuiltInNeoForgePlaceholderService support, ServerPlayer player, String normalizedParams) {
		return switch (normalizedParams) {
			case "prefix" -> support.safe(permissionService.getPlayerPrefix(player.getUUID()));
			case "suffix" -> support.safe(permissionService.getPlayerSuffix(player.getUUID()));
			case "primary_group_name" -> support.safe(permissionService.getGroupName(player.getUUID()));
			case "primary_group_display_name" -> support.safe(permissionService.getGroupDisplayName(player.getUUID()));
			default -> null;
		};
	}

	private String resolveVaultValue(BuiltInNeoForgePlaceholderService support, ServerPlayer player, String normalizedParams) {
		return switch (normalizedParams) {
			case "prefix" -> support.safe(permissionService.getPlayerPrefix(player.getUUID()));
			case "suffix" -> support.safe(permissionService.getPlayerSuffix(player.getUUID()));
			case "primary_group" -> support.safe(permissionService.getGroupName(player.getUUID()));
			default -> null;
		};
	}
}
