package dev.belikhun.luna.core.paper.heartbeat;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.belikhun.luna.core.api.heartbeat.ServerTickStats;
import dev.belikhun.luna.core.api.heartbeat.ServerWorldStats;
import dev.belikhun.luna.core.api.heartbeat.TickRecorder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The half of the heartbeat that only the server thread can answer.
 *
 * Paper publishes its heartbeat asynchronously, and worlds, chunks and entities
 * are all main-thread-only. So the two are separated: this samples on the server
 * thread and parks the result where the publishing thread can read it, and the
 * publisher never touches the world at all.
 *
 * That split is also what bounds the cost. A world scan walks every loaded
 * chunk, which on a busy server is thousands of them; running it on its own
 * timer means the price is paid once per interval no matter how often something
 * asks for a heartbeat.
 */
public final class PaperServerSampler implements Listener {
	/**
	 * How often the worlds are counted, in ticks.
	 *
	 * Matched to the default heartbeat interval: a faster scan would be work
	 * nobody reads, and a slower one would publish the same numbers twice.
	 */
	private static final long WORLD_SCAN_TICKS = 100L;

	private final Plugin plugin;
	private final TickRecorder ticks = new TickRecorder();

	/** Written on the server thread, read on the publishing one. */
	private volatile List<ServerWorldStats> worlds = List.of();

	private int worldTaskId = -1;

	public PaperServerSampler(Plugin plugin) {
		this.plugin = plugin;
	}

	public void start() {
		stop();

		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		worldTaskId = plugin.getServer().getScheduler()
			.runTaskTimer(plugin, this::scanWorlds, 20L, WORLD_SCAN_TICKS)
			.getTaskId();
	}

	public void stop() {
		HandlerList.unregisterAll(this);

		if (worldTaskId != -1) {
			plugin.getServer().getScheduler().cancelTask(worldTaskId);
			worldTaskId = -1;
		}

		worlds = List.of();
	}

	/** The last world scan; empty until the first one has run. */
	public List<ServerWorldStats> worlds() {
		return worlds;
	}

	/** The tick window as it stands. */
	public ServerTickStats ticks() {
		return ticks.snapshot();
	}

	/**
	 * Paper hands the finished tick's own duration, so this is the real cost of
	 * the tick rather than the gap between two of them. Monitor priority: the
	 * measurement should not be part of what it measures.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onTickEnd(ServerTickEndEvent event) {
		ticks.record(event.getTickDuration(), Bukkit.getOnlinePlayers().size());
	}

	private void scanWorlds() {
		List<ServerWorldStats> scanned = new ArrayList<>();

		for (World world : Bukkit.getWorlds()) {
			Chunk[] chunks = world.getLoadedChunks();
			int ticking = 0;
			int nonTicking = 0;

			for (Chunk chunk : chunks) {
				// asking a chunk for its entities is what loads them, so a chunk
				// whose entity data is not in memory is skipped rather than paged in
				// for the sake of a number
				if (!chunk.isEntitiesLoaded()) {
					continue;
				}

				int count = chunk.getEntities().length;

				if (chunk.getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING) {
					ticking += count;
				} else {
					nonTicking += count;
				}
			}

			scanned.add(new ServerWorldStats(world.getName(), chunks.length, ticking, nonTicking));
		}

		worlds = List.copyOf(scanned);
	}
}
