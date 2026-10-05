package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;
import dev.belikhun.luna.core.api.placeholder.PlaceholderHost;
import dev.belikhun.luna.core.api.placeholder.PlayerSample;
import dev.belikhun.luna.core.api.placeholder.ServerSample;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.UUID;

/**
 * The proxy's view of itself and its players.
 *
 * A proxy has no worlds; a player's "world" here is the backend they are on,
 * which is what every network-scoped placeholder wants to know about them.
 */
public final class VelocityPlaceholderHost implements PlaceholderHost<Player> {
	private final ProxyServer proxyServer;
	private final PermissionService permissions;

	public VelocityPlaceholderHost(ProxyServer proxyServer, PermissionService permissions) {
		this.proxyServer = proxyServer;
		this.permissions = permissions;
	}

	@Override
	public UUID idOf(Player player) {
		return player.getUniqueId();
	}

	@Override
	public String nameOf(Player player) {
		return player.getUsername();
	}

	@Override
	public ServerSample sampleServer() {
		Runtime runtime = Runtime.getRuntime();
		java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
		double systemCpu = 0D;
		double processCpu = 0D;

		if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
			systemCpu = percent(sun.getCpuLoad());
			processCpu = percent(sun.getProcessCpuLoad());
		}

		return new ServerSample(
			"proxy",
			"proxy",
			"Proxy",
			null,
			proxyServer.getVersion().getVersion(),
			proxyServer.getPlayerCount(),
			proxyServer.getConfiguration().getShowMaxPlayers(),
			false,
			20D,
			0D,
			Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime()),
			systemCpu,
			processCpu,
			Math.max(0L, runtime.totalMemory() - runtime.freeMemory()),
			Math.max(1L, runtime.maxMemory()),
			0,
			0,
			0,
			0L,
			Map.of(),
			System.currentTimeMillis()
		);
	}

	@Override
	public PlayerSample samplePlayer(Player player) {
		String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
		PlayerSample.Builder builder = PlayerSample.builder(player.getUniqueId(), player.getUsername());

		builder.displayName(MiniMessage.miniMessage().escapeTags(player.getUsername()));
		builder.ping((int) Math.max(0L, player.getPing()));
		builder.world(server, WorldKind.CUSTOM, server);

		if (permissions != null && permissions.isAvailable()) {
			UUID id = player.getUniqueId();
			builder.permissions(
				permissions.getPlayerPrefix(id),
				permissions.getPlayerSuffix(id),
				permissions.getGroupName(id),
				permissions.getGroupDisplayName(id)
			);
		}

		return builder.build();
	}

	private static double percent(double load) {
		if (Double.isNaN(load) || Double.isInfinite(load) || load < 0D) {
			return 0D;
		}

		return Math.max(0D, Math.min(100D, load * 100D));
	}
}
