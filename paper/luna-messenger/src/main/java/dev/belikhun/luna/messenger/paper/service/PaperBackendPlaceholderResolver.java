package dev.belikhun.luna.messenger.paper.service;

import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.ExportingPlaceholderResolver;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionRequest;
import dev.belikhun.luna.core.api.messenger.PlaceholderResolutionResult;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * The Paper exporter: the shared {@link ExportingPlaceholderResolver} with
 * PlaceholderAPI answering the identifiers.
 */
public final class PaperBackendPlaceholderResolver implements BackendPlaceholderResolver {
	private final JavaPlugin plugin;
	private final ExportingPlaceholderResolver delegate;

	public PaperBackendPlaceholderResolver(JavaPlugin plugin, List<String> configuredExportKeys) {
		this.plugin = plugin;
		this.delegate = new ExportingPlaceholderResolver(configuredExportKeys, this::resolve);
	}

	@Override
	public PlaceholderResolutionResult resolve(PlaceholderResolutionRequest request) {
		return delegate.resolve(request);
	}

	private String resolve(UUID playerId, String identifier) {
		Player player = plugin.getServer().getPlayer(playerId);

		if (player == null || !plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			return null;
		}

		try {
			String resolved = PlaceholderAPI.setPlaceholders(player, identifier);
			return resolved == null || resolved.equals(identifier) ? null : resolved;
		} catch (RuntimeException exception) {
			return null;
		}
	}
}
