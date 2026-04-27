package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.level.ServerPlayer;

final class NeoForgePermissionPlaceholderProvider implements NeoForgePlaceholderProvider {
	private final PermissionService permissionService;

	NeoForgePermissionPlaceholderProvider(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	@Override
	public String resolveNativeValue(
		BuiltInNeoForgePlaceholderService support,
		ServerPlayer player,
		String rawIdentifier,
		String normalizedIdentifier,
		NeoForgePlaceholderSnapshot snapshot
	) {
		if (player == null || normalizedIdentifier == null || normalizedIdentifier.isBlank()) {
			return null;
		}

		if (permissionService == null || !permissionService.isAvailable()) {
			return null;
		}

		return switch (normalizedIdentifier) {
			case "luckperms_prefix", "vault_prefix" -> support.safe(permissionService.getPlayerPrefix(player.getUUID()));
			case "luckperms_suffix", "vault_suffix" -> support.safe(permissionService.getPlayerSuffix(player.getUUID()));
			case "luckperms_primary_group_name", "vault_primary_group" -> support.safe(permissionService.getGroupName(player.getUUID()));
			case "luckperms_primary_group_display_name" -> support.safe(permissionService.getGroupDisplayName(player.getUUID()));
			default -> null;
		};
	}
}
