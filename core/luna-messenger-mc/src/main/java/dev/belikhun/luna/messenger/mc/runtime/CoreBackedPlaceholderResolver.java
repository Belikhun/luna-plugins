package dev.belikhun.luna.messenger.mc.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.ExportingPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * The mod-loader exporter: the shared {@link ExportingPlaceholderResolver}
 * with LunaCore's placeholder service answering the identifiers.
 */
final class CoreBackedPlaceholderResolver implements BackendPlaceholderResolver {
	private final DependencyManager dependencyManager;
	private final ExportingPlaceholderResolver delegate;

	CoreBackedPlaceholderResolver(DependencyManager dependencyManager, List<String> configuredExportKeys) {
		this.dependencyManager = dependencyManager;
		this.delegate = new ExportingPlaceholderResolver(configuredExportKeys, this::resolve);
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		return delegate.resolve(request);
	}

	private String resolve(UUID playerId, String identifier) {
		if (dependencyManager == null || playerId == null) {
			return null;
		}

		PlaceholderService placeholders = dependencyManager.resolveOptional(PlaceholderService.class).orElse(null);
		MinecraftServer server = dependencyManager.resolveOptional(MinecraftServer.class).orElse(null);

		if (placeholders == null || server == null || server.getPlayerList() == null) {
			return null;
		}

		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		return player == null ? null : placeholders.resolvePlaceholder(player, identifier);
	}
}
