package dev.belikhun.luna.core.neoforge.placeholder;

import dev.belikhun.luna.core.api.profile.PermissionService;

import java.util.List;

public final class NeoForgePlaceholderProviderFactory {
	private NeoForgePlaceholderProviderFactory() {
	}

	public static List<NeoForgePlaceholderProvider> createDefault(PermissionService permissionService) {
		return List.of(
			new NeoForgePermissionPlaceholderProvider(permissionService),
			new NeoForgeSparkPlaceholderProvider(),
			new NeoForgeImportedPlaceholderProvider(),
			new NeoForgeBuiltinPlaceholderProvider()
		);
	}
}
