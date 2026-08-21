package dev.belikhun.luna.tv.display;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import dev.belikhun.luna.tv.audio.AudioService;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;

/**
 * Decides who is sent which screen.
 *
 * MapEngine screens are packets, not blocks: a player who was never sent one
 * sees an empty wall, and a player who walks away keeps receiving frames until
 * told otherwise. This closes that loop on a slow timer, which is enough
 * because the distance involved is tens of blocks.
 *
 * It is also where the courtesy notice about the voice-chat mod is given, once
 * per session, since that is the moment a player is standing in front of a
 * screen with sound and hearing nothing.
 */
public final class ViewerTracker implements Listener, Runnable {

	private static final long INTERVAL_TICKS = 20L;

	private final JavaPlugin plugin;
	private final ScreenManager screens;
	private final DisplayService displays;
	private final AudioService audio;
	private final Set<UUID> toldAboutVoiceChat = new HashSet<>();

	private int taskId = -1;

	public ViewerTracker(
		JavaPlugin plugin,
		ScreenManager screens,
		DisplayService displays,
		AudioService audio
	) {
		this.plugin = plugin;
		this.screens = screens;
		this.displays = displays;
		this.audio = audio;
	}

	/** Starts the proximity task. */
	public void start() {
		taskId = plugin.getServer().getScheduler()
			.scheduleSyncRepeatingTask(plugin, this, INTERVAL_TICKS, INTERVAL_TICKS);
	}

	/** Stops the proximity task. */
	public void stop() {
		if (taskId != -1) {
			plugin.getServer().getScheduler().cancelTask(taskId);
			taskId = -1;
		}
	}

	@Override
	public void run() {
		screens.tick();

		for (ScreenInstance instance : screens.instances()) {
			if (instance.display() == null) {
				continue;
			}

			sync(instance);
		}
	}

	private void sync(ScreenInstance instance) {
		List<Player> near = screens.nearby(instance);
		Set<UUID> wanted = new HashSet<>();

		for (Player player : near) {
			wanted.add(player.getUniqueId());

			if (!instance.viewers().contains(player.getUniqueId())) {
				displays.show(instance, player);
			}

			if (instance.screen().audio()) {
				maybeMentionVoiceChat(player);
			}
		}

		// copied first: hiding mutates the viewer set
		for (UUID viewer : Set.copyOf(instance.viewers())) {
			if (wanted.contains(viewer)) {
				continue;
			}

			Player player = Bukkit.getPlayer(viewer);

			if (player == null) {
				instance.viewers().remove(viewer);
				continue;
			}

			displays.hide(instance, player);
		}
	}

	private void maybeMentionVoiceChat(Player player) {
		if (audio.canHear(player) || toldAboutVoiceChat.contains(player.getUniqueId())) {
			return;
		}

		toldAboutVoiceChat.add(player.getUniqueId());
		player.sendRichMessage("<gray>ℹ Màn hình này có tiếng, nhưng bạn cần mod "
			+ "<white>Simple Voice Chat</white> để nghe được.</gray>");
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();

		toldAboutVoiceChat.remove(id);

		// a full hide, not just the viewer set: the receiver set holds the Player
		// object itself, and one left behind here is a dead connection that still
		// compares equal to the same player's next session
		for (ScreenInstance instance : screens.instances()) {
			displays.hide(instance, event.getPlayer());
		}
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		for (ScreenInstance instance : screens.instances()) {
			if (!instance.viewers().contains(event.getPlayer().getUniqueId())) {
				continue;
			}

			// the display belongs to the world they left, so stop sending it
			displays.hide(instance, event.getPlayer());
		}
	}
}
