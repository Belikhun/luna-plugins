package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.LunaPlaceholders;
import dev.belikhun.luna.core.api.placeholder.PlaceholderInspector;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the placeholder engine into Paper: samples on the main thread on a
 * fixed cadence, exposes every namespace through PlaceholderAPI, and forgets
 * players as they leave.
 */
public final class PaperPlaceholderModule implements Listener {
	/** Player samples every half second; the server sample every other run. */
	private static final long TICK_PERIOD = 10L;

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PlaceholderRuntime<OfflinePlayer> runtime;
	private final PaperPlaceholderExpansions expansions;
	private final PlaceholderInspector<OfflinePlayer> inspector;
	private BukkitTask samplerTask;
	private boolean serverTurn = true;

	public PaperPlaceholderModule(JavaPlugin plugin, BackendStatusView statusView, ConfigStore configStore, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger.scope("Placeholders");
		PaperPlaceholderHost host = new PaperPlaceholderHost(plugin, statusView, configStore, logger);
		this.runtime = new PlaceholderRuntime<>(host, this.logger::warn);
		this.inspector = new PlaceholderInspector<>(runtime);
		this.expansions = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
			? new PaperPlaceholderExpansions(plugin, runtime, logger)
			: null;
	}

	/** Registers the built-ins, starts sampling and opens the PlaceholderAPI surface. */
	public void start() {
		runtime.register("luna-core", LunaPlaceholders.<OfflinePlayer>specs());

		if (expansions != null) {
			runtime.onChange(expansions::sync);
			expansions.sync();
		} else {
			logger.warn("PlaceholderAPI không hoạt động. Placeholder luna vẫn resolve nội bộ nhưng không xuất qua PAPI.");
		}

		Bukkit.getPluginManager().registerEvents(this, plugin);
		runtime.engine().refreshServer();
		samplerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, TICK_PERIOD, TICK_PERIOD);
		logger.audit("Placeholder engine đã chạy: " + runtime.registry().namespaces() + ".");
	}

	public PlaceholderRuntime<OfflinePlayer> runtime() {
		return runtime;
	}

	public PlaceholderInspector<OfflinePlayer> inspector() {
		return inspector;
	}

	/** Stops sampling and withdraws every expansion. */
	public void shutdown() {
		if (samplerTask != null) {
			samplerTask.cancel();
			samplerTask = null;
		}

		if (expansions != null) {
			expansions.unregisterAll();
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		runtime.engine().forgetPlayer(event.getPlayer().getUniqueId());
	}

	private void sample() {
		if (serverTurn) {
			runtime.engine().refreshServer();
		}

		serverTurn = !serverTurn;
		List<OfflinePlayer> online = new ArrayList<>(Bukkit.getOnlinePlayers());
		runtime.engine().refreshPlayers(online);
	}
}
