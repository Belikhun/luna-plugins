package dev.belikhun.luna.tabbridge.fabric.runtime;

import dev.belikhun.luna.core.mc.placeholder.PlaceholderService;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class FabricTabBridgePlaceholderUpdater implements AutoCloseable {
	private static final long REFRESH_INTERVAL_MILLIS = 50L;

	private final MinecraftServer server;
	private final FabricTabBridgeRuntime runtime;
	private final FabricTabBridgeRelationalPlaceholderSource relationalPlaceholderSource;
	private final PlaceholderService placeholderService;
	private final ScheduledExecutorService refreshExecutor;
	private volatile boolean closed;

	public FabricTabBridgePlaceholderUpdater(MinecraftServer server, FabricTabBridgeRuntime runtime, FabricTabBridgeRelationalPlaceholderSource relationalPlaceholderSource, PlaceholderService placeholderService) {
		this.server = Objects.requireNonNull(server, "server");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.relationalPlaceholderSource = Objects.requireNonNull(relationalPlaceholderSource, "relationalPlaceholderSource");
		this.placeholderService = Objects.requireNonNull(placeholderService, "placeholderService");
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-tabbridge-fabric-placeholders");
			thread.setDaemon(true);
			return thread;
		});
		this.refreshExecutor.scheduleAtFixedRate(
			() -> {
				if (closed) {
					return;
				}

				this.server.execute(() -> {
					if (!closed) {
						refreshOnlinePlayers();
					}
				});
			},
			REFRESH_INTERVAL_MILLIS,
			REFRESH_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	public void refreshOnlinePlayers() {
		if (closed) {
			return;
		}

		// the shared statistics are not re-sampled here: LunaCore refreshes them on
		// its own tick, so doing it again per pass would count the world's entities
		// twenty times a second instead of twice
		for (ServerPlayer player : PlayerLookup.all(server)) {
			refreshPlayer(player);
		}
	}

	public void refreshPlayer(ServerPlayer player) {
		if (closed || player == null) {
			return;
		}

		runtime.updatePlayerPlaceholders(player, placeholderService.snapshot(player, runtime.requestedPlaceholderIdentifiers(player.getUUID())));
		runtime.updatePlayerRelationalPlaceholders(player, relationalPlaceholderSource.resolve(player));
	}

	public String resolvePlaceholder(ServerPlayer player, String identifier) {
		if (closed) {
			return null;
		}

		return placeholderService.resolvePlaceholder(player, identifier);
	}

	@Override
	public void close() {
		closed = true;
		refreshExecutor.shutdownNow();
	}
}
