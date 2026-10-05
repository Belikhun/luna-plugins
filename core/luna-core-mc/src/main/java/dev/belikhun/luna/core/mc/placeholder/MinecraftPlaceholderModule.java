package dev.belikhun.luna.core.mc.placeholder;

import dev.belikhun.luna.core.api.heartbeat.BackendIdentity;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.CompatPlaceholders;
import dev.belikhun.luna.core.api.placeholder.LunaPlaceholders;
import dev.belikhun.luna.core.api.placeholder.PlaceholderInspector;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Wires the placeholder engine into a mod loader: samples on the server tick,
 * publishes the {@code luna} built-ins and the PlaceholderAPI stand-ins, and
 * offers the {@link PlaceholderService} face other modules use.
 *
 * The bootstrap calls {@link #onServerTick()} every tick and
 * {@link #forgetPlayer(UUID)} on logout; the module decides the cadence.
 */
public final class MinecraftPlaceholderModule {
	/** Player samples every half second; the server sample once a second. */
	private static final int PLAYER_REFRESH_TICKS = 10;
	private static final int SERVER_REFRESH_TICKS = 20;

	private final MinecraftServer server;
	private final LunaLogger logger;
	private final PlaceholderRuntime<ServerPlayer> runtime;
	private final PlaceholderService service;
	private final PlaceholderInspector<ServerPlayer> inspector;
	private final PermissionService permissions;
	private int ticks;
	private volatile boolean sampledOnce;

	public MinecraftPlaceholderModule(
		MinecraftServer server,
		BackendIdentity identity,
		BackendStatusView statusView,
		PermissionService permissions,
		GameReads reads,
		LunaLogger logger
	) {
		this.server = server;
		this.logger = logger.scope("Placeholders");
		this.permissions = permissions;
		MinecraftPlaceholderHost host = new MinecraftPlaceholderHost(server, identity, statusView, permissions, reads, logger);
		this.runtime = new PlaceholderRuntime<>(host, this.logger::warn);
		this.service = new RuntimePlaceholderService(runtime, this::ensureSampled);
		this.inspector = new PlaceholderInspector<>(runtime);
	}

	/** Registers the built-ins and takes the first sample. */
	public void start() {
		runtime.register("luna-core", LunaPlaceholders.<ServerPlayer>specs());
		runtime.register("luna-core-compat", CompatPlaceholders.<ServerPlayer>specs());
		ensureSampled();
		logger.audit("Placeholder engine đã chạy: " + runtime.registry().namespaces() + ".");
	}

	public PlaceholderRuntime<ServerPlayer> runtime() {
		return runtime;
	}

	public PlaceholderService service() {
		return service;
	}

	public PlaceholderInspector<ServerPlayer> inspector() {
		return inspector;
	}

	/** The permission service the bootstrap gave this module, or null. */
	public PermissionService permissions() {
		return permissions;
	}

	/** Called by the bootstrap at the end of every server tick. */
	public void onServerTick() {
		ticks++;

		if (ticks % SERVER_REFRESH_TICKS == 0) {
			runtime.engine().refreshServer();
			sampledOnce = true;
		}

		if (ticks % PLAYER_REFRESH_TICKS == 0) {
			runtime.engine().refreshPlayers(server.getPlayerList().getPlayers());
		}
	}

	/** Drops a player's sample on logout. */
	public void forgetPlayer(UUID playerId) {
		runtime.engine().forgetPlayer(playerId);
	}

	private void ensureSampled() {
		if (sampledOnce) {
			return;
		}

		if (server.isSameThread()) {
			runtime.engine().refreshServer();
			runtime.engine().refreshPlayers(server.getPlayerList().getPlayers());
			sampledOnce = true;
			return;
		}

		server.execute(this::ensureSampled);
	}
}
