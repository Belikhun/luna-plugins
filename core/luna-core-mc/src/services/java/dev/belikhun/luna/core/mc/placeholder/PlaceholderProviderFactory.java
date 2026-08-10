package dev.belikhun.luna.core.mc.placeholder;

import dev.belikhun.luna.core.api.profile.PermissionService;

import java.util.List;

public final class PlaceholderProviderFactory {
	private PlaceholderProviderFactory() {
	}

	public static List<ServerPlaceholderProvider> createDefault(PermissionService permissionService) {
		return List.of(
			new PermissionPlaceholderProvider(permissionService),
			new SparkPlaceholderProvider(),
			new ImportedPlaceholderProvider(),
			new BuiltinPlaceholderProvider()
		);
	}
}
