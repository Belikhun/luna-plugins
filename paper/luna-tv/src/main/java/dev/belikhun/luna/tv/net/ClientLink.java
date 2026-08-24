package dev.belikhun.luna.tv.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;
import dev.belikhun.luna.tv.TvDebug;
import dev.belikhun.luna.tv.browser.CdpBrowser;
import dev.belikhun.luna.tv.screen.ScreenInstance;
import dev.belikhun.luna.tv.screen.ScreenManager;
import dev.belikhun.luna.tv.screen.ScreenState;
import dev.belikhun.luna.tv.screen.TvScreen;
import dev.belikhun.luna.tv.stream.StreamServer;

/**
 * The conversation with the client mod.
 *
 * Only the small things travel over the game connection: who has the mod, where
 * the screens are, and what the player just did to one. The frames go over
 * their own socket, which is the entire point - a pointer move is a few bytes
 * and belongs on the connection that is already there, while a frame is a
 * hundred kilobytes and must never be queued in front of somebody's movement.
 *
 * A player who says hello stops being sent MapEngine maps and is served the
 * stream instead. Everybody else keeps the maps exactly as before: the wall has
 * to stay visible to a vanilla client, so the two paths run side by side and
 * the same screen is simply drawn twice, once per audience.
 */
public final class ClientLink implements PluginMessageListener {

	/** Client announces itself and its protocol version. */
	public static final String HELLO = "lunatv:hello";

	/** Server describes the screens, and how to fetch them. */
	public static final String SCREENS = "lunatv:screens";

	/** Client reports pointer, wheel and keyboard activity. */
	public static final String INPUT = "lunatv:input";

	/** How long after a click or a keypress to look at where focus ended up. */
	private static final long FOCUS_RECHECK_TICKS = 16L;

	/** Bumped when the wire format changes in a way an old client misreads. */
	public static final int PROTOCOL = 8;

	private static final byte INPUT_POINTER_DOWN = 1;
	private static final byte INPUT_POINTER_UP = 2;
	private static final byte INPUT_POINTER_MOVE = 3;
	private static final byte INPUT_SCROLL = 4;
	private static final byte INPUT_KEY = 5;
	private static final byte INPUT_TEXT = 6;

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final ScreenManager screens;
	private final StreamServer stream;

	/** Players known to be running the mod, so maps are withheld from them. */
	private final Set<UUID> modded = ConcurrentHashMap.newKeySet();

	private volatile TvConfig config;

	public ClientLink(
		JavaPlugin plugin,
		LunaLogger logger,
		ScreenManager screens,
		StreamServer stream,
		TvConfig config
	) {
		this.plugin = plugin;
		this.logger = logger;
		this.screens = screens;
		this.stream = stream;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/** Registers the channels. */
	public void start() {
		plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SCREENS);
		plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, HELLO, this);
		plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, INPUT, this);
	}

	/** Unregisters them, and forgets who had the mod. */
	public void stop() {
		plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin);
		plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin);
		modded.clear();
	}

	/**
	 * Whether a player renders screens themselves.
	 *
	 * @param player the player
	 * @return true when they have the mod and should not be sent maps
	 */
	public boolean handled(UUID player) {
		return modded.contains(player);
	}

	/** Forgets a player, on quit. */
	public void forget(UUID player) {
		modded.remove(player);
		stream.revoke(player);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		try {
			if (HELLO.equals(channel)) {
				onHello(player, message);

				return;
			}

			if (INPUT.equals(channel)) {
				onInput(player, message);
			}
		} catch (Throwable throwable) {
			TvDebug.log("client message hỏng từ " + player.getName() + ": " + throwable);
		}
	}

	private void onHello(Player player, byte[] message) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
		int version = in.readInt();

		if (version != PROTOCOL) {
			logger.info("Mod của " + player.getName() + " dùng giao thức " + version
				+ ", máy chủ dùng " + PROTOCOL + "; bỏ qua, người chơi vẫn xem bằng bản đồ.");

			return;
		}

		modded.add(player.getUniqueId());
		logger.info("Người chơi " + player.getName() + " có mod Luna TV, chuyển sang luồng hình.");

		// the maps they may already be holding are taken back, or the wall would
		// be drawn twice for them
		screens.hideFrom(player);
		sendScreens(player);
	}

	/**
	 * Tells one player about every screen, and how to fetch each one.
	 *
	 * Sent on hello and whenever the set of screens changes; the client treats
	 * it as the whole truth and forgets anything not in it.
	 *
	 * @param player who to tell
	 */
	public void sendScreens(Player player) {
		if (!modded.contains(player.getUniqueId())) {
			return;
		}

		String token = stream.issueToken(player.getUniqueId());
		String base = config.streamPublicUrl();

		if (base.isBlank()) {
			base = "http://" + config.streamHost() + ":" + config.streamPort();
		}

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);

			out.writeInt(PROTOCOL);
			out.writeUTF(base);
			out.writeUTF(token);
			// Whether to ask for <name>/h264 instead of <name>. Decided here and
			// not by the client, because it turns on whether this server found an
			// ffmpeg to encode with; the client still says no when it has no
			// decoder of its own, and both ends fall back to the picture stream.
			out.writeBoolean(stream.h264());
			// How far a screen is worth fetching from. The same knob the map path
			// spawns and despawns on, so a mod client drops its stream at the
			// distance a vanilla one loses the wall.
			out.writeDouble(config.spawnDistance());
			// How close a player must be for their aim to reach the page at all.
			// The same knob the map path uses, so the two audiences lose control
			// of a screen at the same distance.
			out.writeDouble(config.interactDistance());

			var all = screens.instances();

			out.writeInt(all.size());

			for (ScreenInstance instance : all) {
				TvScreen screen = instance.screen();

				out.writeUTF(screen.name());
				out.writeUTF(screen.world());
				// the client has never heard of a world called "survival"; it knows
				// the same place as minecraft:overworld, so the key travels too
				out.writeUTF(dimensionKey(screen.world()));
				out.writeInt(screen.cornerA().getBlockX());
				out.writeInt(screen.cornerA().getBlockY());
				out.writeInt(screen.cornerA().getBlockZ());
				out.writeInt(screen.cornerB().getBlockX());
				out.writeInt(screen.cornerB().getBlockY());
				out.writeInt(screen.cornerB().getBlockZ());
				out.writeUTF(screen.facing().name());
				// the wall's own grid: mapsWide x 128 by mapsHigh x 128, the exact
				// resolution MapEngine renders, so a mod client's quad lands on the
				// same pixel boundaries a vanilla client sees
				out.writeInt(screen.pixelWidth());
				out.writeInt(screen.pixelHeight());

				// what the frames actually arrive at. Equal to the wall size at
				// scale 1; smaller when the operator scaled the capture down, and
				// then the client stretches. Sent so the texture is allocated once
				// rather than reallocated on the first frame.
				CdpBrowser feed = instance.browser();

				out.writeInt(feed == null ? screen.pixelWidth() : feed.captureWidth());
				out.writeInt(feed == null ? screen.pixelHeight() : feed.captureHeight());
				out.writeBoolean(instance.state() == ScreenState.RUNNING);
				out.writeBoolean(screen.audio());
				// two channels interleaved in the PCM rather than one, so the
				// client knows to split it and place the halves at the wall's
				// edges the way the voice-chat path already does
				out.writeBoolean(screen.stereo());
				// how far the sound carries. The same number voice chat gets, so
				// a screen fades out at one distance for everybody watching it.
				out.writeInt(screens.effectiveAudioRange(screen));
				out.writeInt(screen.volume());
				out.writeBoolean(screen.locked());
				// the map path bakes brightness into the pixels it dithers, which
				// happens after the JPEG the stream carries, so the client has to
				// apply it itself
				out.writeInt(screen.brightness());
				out.writeInt(screen.glow());
				out.writeBoolean(instance.keyboardFocus());
			}

			player.sendPluginMessage(plugin, SCREENS, bytes.toByteArray());
		} catch (IOException exception) {
			logger.warn("Không gửi được danh sách màn hình cho " + player.getName()
				+ ": " + exception.getMessage());
		}
	}

	/**
	 * The dimension key of a world, as a client would name it.
	 *
	 * @param world the server's own name for the world
	 * @return the namespaced key, or empty when no such world is loaded
	 */
	private String dimensionKey(String world) {
		var found = plugin.getServer().getWorld(world);

		if (found == null) {
			return "";
		}

		return found.getKey().toString();
	}

	/**
	 * Asks the page whether it now has a text field focused, and tells clients.
	 *
	 * Only after a click, which is when focus almost always moves, rather than on
	 * a timer: the answer costs a round trip to Chromium and a screen nobody is
	 * touching does not change its mind.
	 */
	private void checkFocus(ScreenInstance instance, CdpBrowser browser) {
		ask(instance, browser);

		// And again in a moment. A click on a link and a press of enter both
		// answer truthfully about the page that is still on screen, then navigate
		// away from it; the second look is the one that sees where focus landed.
		plugin.getServer().getScheduler().runTaskLater(plugin,
			() -> ask(instance, browser), FOCUS_RECHECK_TICKS);
	}

	private void ask(ScreenInstance instance, CdpBrowser browser) {
		if (!browser.alive()) {
			return;
		}

		browser.editableFocused().thenAccept(focused -> plugin.getServer().getScheduler()
			.runTask(plugin, () -> screens.keyboardFocus(instance, focused)));
	}

	/** Re-sends the registry to everyone running the mod. */
	public void broadcastScreens() {
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			sendScreens(player);
		}
	}

	/**
	 * Applies an input event the client already resolved to a pixel.
	 *
	 * The client knows where its own cursor is and whether a button is held, so
	 * unlike the map path there is nothing to infer here: no ray-trace, no
	 * guessing a release from a stream of swings. That is what makes a real
	 * drag possible.
	 */
	private void onInput(Player player, byte[] message) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
		String name = in.readUTF();
		byte kind = in.readByte();

		Optional<ScreenInstance> found = screens.find(name);

		if (found.isEmpty()) {
			return;
		}

		ScreenInstance instance = found.get();

		if (instance.state() != ScreenState.RUNNING) {
			return;
		}

		if (instance.screen().locked()
			&& !player.hasPermission("lunatv.control")) {
			return;
		}

		CdpBrowser browser = instance.browser();

		if (browser == null) {
			return;
		}

		switch (kind) {
			case INPUT_POINTER_DOWN -> {
				browser.pointerDown(
					clamp(in.readInt(), browser.width()), clamp(in.readInt(), browser.height()),
					in.readBoolean());
				checkFocus(instance, browser);
			}
			case INPUT_POINTER_UP -> {
				browser.pointerUp(
					clamp(in.readInt(), browser.width()), clamp(in.readInt(), browser.height()),
					in.readBoolean());
				checkFocus(instance, browser);
			}
			case INPUT_POINTER_MOVE -> browser.pointerMove(
				clamp(in.readInt(), browser.width()), clamp(in.readInt(), browser.height()),
				in.readInt());
			case INPUT_SCROLL -> browser.scroll(
				clamp(in.readInt(), browser.width()), clamp(in.readInt(), browser.height()),
				in.readInt());
			case INPUT_KEY -> {
				browser.key(in.readUTF(), in.readInt());
				// Enter is how a search box stops being a search box. Without a
				// re-check here the keyboard stays captured on the results page,
				// where there is nothing focused to capture it for.
				checkFocus(instance, browser);
			}
			case INPUT_TEXT -> browser.type(in.readUTF());
			default -> {
				// an unknown event from a newer client is ignored, not fatal
			}
		}
	}

	private static int clamp(int value, int limit) {
		return Math.max(0, Math.min(limit - 1, value));
	}
}
