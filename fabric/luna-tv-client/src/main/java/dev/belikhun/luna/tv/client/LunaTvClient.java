package dev.belikhun.luna.tv.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.belikhun.luna.tv.client.net.TvPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import dev.belikhun.luna.tv.client.input.ScreenInput;
import dev.belikhun.luna.tv.client.render.Feedback;
import dev.belikhun.luna.tv.client.render.ScreenQuad;
import dev.belikhun.luna.tv.client.render.ScreenSink;
import dev.belikhun.luna.tv.client.render.ScreenTexture;
import dev.belikhun.luna.tv.client.render.WorldHook;
import dev.belikhun.luna.tv.client.screen.Frame;
import dev.belikhun.luna.tv.client.screen.H264Feed;
import dev.belikhun.luna.tv.client.screen.NativeFfmpeg;
import dev.belikhun.luna.tv.client.screen.StreamFeed;
import dev.belikhun.luna.tv.client.screen.TvScreen;
import dev.belikhun.luna.tv.client.screen.VideoFeed;
import dev.belikhun.luna.tv.client.sound.ScreenSound;
import dev.belikhun.luna.tv.client.sound.SoundBus;

/**
 * Draws Luna TV screens from the server's video stream.
 *
 * The server keeps sending map items to anybody without this mod, so nothing is
 * lost by not having it; saying hello is what switches a player over. From then
 * on the frames arrive on their own HTTP connection and never touch the game
 * connection, which is the point: a map frame is megabytes and sits in front of
 * your movement packets, and a JPEG on another socket does not.
 */
public final class LunaTvClient implements ClientModInitializer {

	/** Must match ClientLink.PROTOCOL on the server. */
	private static final int PROTOCOL = 8;

	/** Extra blocks a screen keeps its stream for once it already has one. */
	private static final double HYSTERESIS = 8.0;

	/**
	 * How far in from each edge a stereo screen's speakers sit, as a fraction of
	 * its width. The same inset the server uses for its voice-chat channels, so
	 * the stage is the same width whichever path a listener is on.
	 */
	private static final double SPEAKER_INSET = 0.15;

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	private final Map<String, Live> screens = new LinkedHashMap<>();
	private final SoundBus sounds = new SoundBus();

	// volatile: the feeds build their URLs from these on their own threads,
	// every attempt, so a fresh token from a re-sent registry reaches a feed
	// that is mid-retry without anybody rebuilding it
	private volatile String streamBase = "";
	private volatile String token = "";

	/** Whether the server can encode H.264; it says so in the registry. */
	private boolean serverH264;

	/** How far a screen is worth fetching from; the server's own spawn distance. */
	private double viewDistance = 48.0;

	/** False when this Minecraft version gives us no way to draw. */
	private boolean drawing;

	/** Set once the tick has thrown, so the failure is reported one time only. */
	private boolean tickFailed;

	/** Client ticks since the last metrics line; twenty of them is a second. */
	private int metricTicks;

	@Override
	public void onInitializeClient() {
		Compat.registerPayloads();

		ClientPlayNetworking.registerGlobalReceiver(TvPayload.SCREENS,
			(payload, context) -> context.client().execute(() -> onScreens(payload.bytes())));

		drawing = installHook();

		// Saying hello is what makes the server stop sending map items, so a
		// client that cannot draw must stay quiet: a broken picture is worse than
		// the maps it would replace, and the maps still work.
		if (!drawing) {
			return;
		}

		Compat.installHud();
		Compat.installOutline();
		ScreenInput.install(this::pointable);
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());

		// the server only starts streaming to players who ask, so a vanilla
		// server or one without the plugin simply never answers and nothing here
		// ever runs
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sayHello());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/**
	 * Subscribes to the world render event, if this version still has one.
	 *
	 * Fabric removed the world render events in the 1.21.9 port and brought them
	 * back under a different package in 1.21.10, so on those versions the class
	 * this hook needs is not there at all. That must not be fatal: the mod simply
	 * has nothing to draw with, and the server keeps sending map items.
	 *
	 * @return whether the screens can be drawn
	 */
	private boolean installHook() {
		try {
			WorldHook.install(this::draw);
			LOGGER.info("Luna TV ready: world render hook installed");

			return true;
		} catch (Throwable unsupported) {
			LOGGER.error("Luna TV cannot draw on this Minecraft version: Fabric's world render"
				+ " events are missing ({}). Screens stay on map items.", unsupported.toString());

			return false;
		}
	}

	/**
	 * The per-tick work, behind a guard.
	 *
	 * Nothing this mod does is worth a crash. It draws a television: if it
	 * cannot, the wall should go blank and the game should carry on, and the
	 * player should be able to read why afterwards. An unguarded throw here
	 * ended the client outright when Minecraft renamed a camera accessor
	 * between two patch versions, which is a thing that will happen again.
	 *
	 * Reported once rather than every tick, because twenty identical stack
	 * traces a second is not a log, it is a denial of one.
	 */
	private void tick() {
		try {
			follow();
		} catch (Throwable throwable) {
			if (!tickFailed) {
				tickFailed = true;
				LOGGER.error("Luna TV tick failed; screens are stopping, the game is not", throwable);
			}

			shutdown();
		}
	}

	/** Drops every screen and its sound, leaving the mod idle but harmless. */
	private void shutdown() {
		try {
			clear();
		} catch (Throwable ignored) {
			// already failing; there is nothing better to try
		}
	}

	private void draw(ScreenSink sink) {
		try {
			render(sink);
		} catch (Throwable throwable) {
			LOGGER.error("Luna TV render failed", throwable);
		}
	}

	private void sayHello() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);

			out.writeInt(PROTOCOL);
			ClientPlayNetworking.send(new TvPayload(TvPayload.HELLO, bytes.toByteArray()));
			LOGGER.info("Luna TV said hello, protocol {}", PROTOCOL);
		} catch (IOException impossible) {
			LOGGER.error("Luna TV hello failed", impossible);
		}
	}

	/**
	 * Rebuilds the screen list from the server's registry.
	 *
	 * The message is the whole truth, so a screen missing from it is gone and
	 * its feed is closed; one that is still there keeps its feed unless
	 * something about it changed.
	 */
	private void onScreens(byte[] message) {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
			int version = in.readInt();

			if (version != PROTOCOL) {
				LOGGER.warn("Luna TV server speaks protocol {}, this mod speaks {}", version, PROTOCOL);

				return;
			}

			streamBase = in.readUTF();
			token = in.readUTF();
			serverH264 = in.readBoolean();
			viewDistance = in.readDouble();
			ScreenInput.reach(in.readDouble());

			int count = in.readInt();
			List<TvScreen> fresh = new ArrayList<>(count);

			for (int index = 0; index < count; index++) {
				fresh.add(new TvScreen(
					in.readUTF(), in.readUTF(), in.readUTF(),
					in.readInt(), in.readInt(), in.readInt(),
					in.readInt(), in.readInt(), in.readInt(),
					in.readUTF(),
					in.readInt(), in.readInt(),
					in.readInt(), in.readInt(),
					in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readInt(),
					in.readInt(), in.readBoolean(),
					in.readInt(), in.readInt(), in.readBoolean()));
			}

			LOGGER.info("Luna TV: server listed {} screen(s), streaming from {}", count, streamBase);
			apply(fresh);
		} catch (Throwable malformed) {
			LOGGER.error("Luna TV registry unreadable", malformed);
		}
	}

	private void apply(List<TvScreen> fresh) {
		Map<String, Live> next = new LinkedHashMap<>();

		for (TvScreen screen : fresh) {
			Live existing = screens.remove(screen.name());

			if (existing != null && existing.matches(screen)) {
				existing.screen = screen;
				next.put(screen.name(), existing);

				continue;
			}

			if (existing != null) {
				silence(existing);
				existing.close();
			}

			Live live = new Live(screen);

			// Whether to actually fetch it is follow()'s to decide, every tick:
			// a screen that is off now may be switched on later, and one across
			// the map is not worth a socket until somebody walks up to it.
			next.put(screen.name(), live);
		}

		for (Live orphan : screens.values()) {
			silence(orphan);
			orphan.close();
		}

		screens.clear();
		screens.putAll(next);
	}

	private void clear() {
		Feedback.reset();
		sounds.stop();

		for (Live live : screens.values()) {
			live.sound = null;
			live.close();
		}

		screens.clear();
		streamBase = "";
		token = "";
	}

	/**
	 * Opens and closes the streams as the player moves and screens switch on.
	 *
	 * Run every tick rather than only when the server speaks, because two of the
	 * three things that decide whether a screen is worth fetching - where the
	 * player is standing, and which world they are in - the server never tells us
	 * about.
	 */
	private void follow() {
		if (screens.isEmpty()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client.player == null) {
			return;
		}

		String dimension = Compat.dimensionKey();
		Vec3 here = client.player.position();

		// taken once, on the only thread allowed to look at the world; the mixer
		// thread reads this snapshot and never Minecraft itself
		sounds.listener(Compat.listener(), Compat.mediaVolume());

		for (Live live : screens.values()) {
			boolean wanted = live.screen.running()
				&& live.screen.flat()
				&& live.screen.dimension().equals(dimension)
				&& within(live, here);

			if (wanted != (live.feed != null)) {
				if (wanted) {
					open(live);
				} else {
					live.release();
					LOGGER.info("Luna TV screen {}: stream stopped", live.screen.name());
				}
			}

			hear(live, dimension, here);
		}

		metricTicks++;

		if (metricTicks >= 20) {
			metricTicks = 0;
			report();
		}
	}

	/**
	 * One line per second per playing screen: what arrived, what was shown.
	 *
	 * The counters are cumulative on the feed, so the line prints the change
	 * since the last one, which is a per-second rate by construction. "drawn"
	 * counts frames the renderer actually took; decoded minus drawn minus
	 * dropped is time the picture sat waiting, and a dropped column that grows
	 * says the decoder is outrunning the renderer, which is the number that
	 * tells slow-decode apart from slow-draw.
	 */
	private void report() {
		for (Live live : screens.values()) {
			VideoFeed feed = live.feed;

			if (feed == null) {
				continue;
			}

			long decoded = feed.framesDecoded();
			long dropped = feed.framesDropped();
			long bytes = feed.bytesReceived();
			long shown = live.shown;

			long decodedNow = decoded - live.lastDecoded;
			long droppedNow = dropped - live.lastDropped;
			long shownNow = shown - live.lastShown;
			double megabits = (bytes - live.lastBytes) * 8.0 / 1_000_000.0;

			live.lastDecoded = decoded;
			live.lastDropped = dropped;
			live.lastBytes = bytes;
			live.lastShown = shown;

			String sound = live.sound == null
				? "no sound"
				: "audio q=" + live.sound.buffered();

			LOGGER.info("Luna TV {}: {} {} fps decoded, {} drawn, {} dropped, {} Mbit/s, {}",
				live.screen.name(), feed.codec(), decodedNow, shownNow, droppedNow,
				String.format(java.util.Locale.ROOT, "%.1f", megabits), sound);
		}
	}

	/**
	 * Opens and closes a screen's sound as the player moves.
	 *
	 * Judged separately from the picture on purpose: the two have their own
	 * radii, so a screen can perfectly well be audible from behind a corner it
	 * cannot be seen from, and pinning the sound to the picture's own reach would
	 * make that a rule rather than a setting.
	 */
	private void hear(Live live, String dimension, Vec3 here) {
		boolean audible = live.screen.running()
			&& live.screen.audio()
			&& live.screen.flat()
			&& live.screen.dimension().equals(dimension)
			&& audibleFrom(live, here);

		// the split into one channel or two is decided when the sources are
		// opened, so a screen switched between mono and stereo needs new ones
		if (live.sound != null && live.sound.stereo() != live.screen.stereo()) {
			silence(live);
		}

		if (audible && live.sound == null) {
			listen(live);
		} else if (!audible && live.sound != null) {
			silence(live);
			LOGGER.info("Luna TV screen {}: sound stopped", live.screen.name());
		}

		if (live.sound != null) {
			live.sound.aim(speakers(live), live.screen.volume(), live.screen.audioRange());

			// A television and the game's soundtrack are two pieces of music at
			// once. The screen is the one somebody chose to stand in front of,
			// so the game's own yields; stopped every tick, because the music
			// manager schedules a new song the moment the old one is gone.
			Compat.suppressMusic();
		}
	}

	private void listen(Live live) {
		String path = "/tv/" + live.screen.name() + "/audio";

		live.sound = new ScreenSound(live.screen.name(),
			() -> streamBase + path + "?t=" + token, live.screen.stereo());
		sounds.add(live.sound);
		LOGGER.info("Luna TV screen {}: {} sound within {} blocks, fetching {}{}",
			live.screen.name(), live.screen.stereo() ? "stereo" : "mono",
			live.screen.audioRange(), streamBase, path);
	}

	private void silence(Live live) {
		if (live.sound == null) {
			return;
		}

		sounds.remove(live.sound);
		live.sound = null;
	}

	/**
	 * Where a screen's channels sound from.
	 *
	 * Mono speaks from the middle of the picture. Stereo speaks from two points
	 * just inside its edges, which is what turns a wall into a stage instead of a
	 * single point that happens to be wide.
	 *
	 * @param live the screen
	 * @return x, y, z per channel, left first
	 */
	private double[] speakers(Live live) {
		ScreenQuad quad = live.quad;

		if (!live.screen.stereo()) {
			return new double[] {
				quad.at(0.5, 0.5, 0), quad.at(0.5, 0.5, 1), quad.at(0.5, 0.5, 2),
			};
		}

		double right = 1.0 - SPEAKER_INSET;

		return new double[] {
			quad.at(SPEAKER_INSET, 0.5, 0), quad.at(SPEAKER_INSET, 0.5, 1), quad.at(SPEAKER_INSET, 0.5, 2),
			quad.at(right, 0.5, 0), quad.at(right, 0.5, 1), quad.at(right, 0.5, 2),
		};
	}

	/**
	 * Whether a screen is close enough to be worth hearing.
	 *
	 * Wider on the way out than on the way in, like the picture, so standing at
	 * the edge of the radius does not open and close a socket every tick.
	 */
	private boolean audibleFrom(Live live, Vec3 here) {
		double limit = live.screen.audioRange() + (live.sound == null ? 0.0 : HYSTERESIS);
		double x = live.quad.at(0.5, 0.5, 0) - here.x;
		double y = live.quad.at(0.5, 0.5, 1) - here.y;
		double z = live.quad.at(0.5, 0.5, 2) - here.z;

		return x * x + y * y + z * z <= limit * limit;
	}

	/**
	 * Whether a screen is close enough to be worth a socket.
	 *
	 * The threshold is wider on the way out than on the way in, so somebody
	 * standing exactly at the edge does not open and close the stream once a tick.
	 */
	private boolean within(Live live, Vec3 here) {
		double limit = live.feed == null ? viewDistance : viewDistance + HYSTERESIS;
		double x = live.quad.at(0.5, 0.5, 0) - here.x;
		double y = live.quad.at(0.5, 0.5, 1) - here.y;
		double z = live.quad.at(0.5, 0.5, 2) - here.z;

		return x * x + y * y + z * z <= limit * limit;
	}

	/**
	 * Starts fetching a screen, in whichever codec both ends can manage.
	 *
	 * H.264 needs the server to have an encoder and this machine to have a
	 * decoder, and either may be missing. The stream of whole JPEGs is what is
	 * left when one is, and it always works, so it is the fallback rather than
	 * an error: a wall that costs too much bandwidth still beats a blank one.
	 */
	private void open(Live live) {
		java.nio.file.Path decoder = serverH264 ? NativeFfmpeg.binary() : null;
		String name = live.screen.name();
		String path = "/tv/" + name + (decoder != null ? "/h264" : "");

		live.announced = false;
		live.feed = decoder != null
			? new H264Feed(name, () -> streamBase + path + "?t=" + token, decoder,
				live.screen.frameWidth(), live.screen.frameHeight())
			: new StreamFeed(name, () -> streamBase + path + "?t=" + token,
				live.screen.frameWidth(), live.screen.frameHeight());

		live.feed.start();
		LOGGER.info("Luna TV screen {}: {}x{} {} in {}, fetching {}{}", name,
			live.screen.frameWidth(), live.screen.frameHeight(), live.feed.codec(),
			live.screen.dimension(), streamBase, path);
	}

	/**
	 * The screens a crosshair could be on right now.
	 *
	 * Only the ones being drawn: a screen in another dimension, or one whose feed
	 * never started, is not on any wall the player can see and must not swallow
	 * their clicks.
	 *
	 * @return one entry per pointable screen
	 */
	private List<ScreenInput.Target> pointable() {
		String dimension = Compat.dimensionKey();
		List<ScreenInput.Target> targets = new ArrayList<>();

		for (Live live : screens.values()) {
			if (live.feed == null || !live.screen.dimension().equals(dimension)) {
				continue;
			}

			targets.add(new ScreenInput.Target(live.screen.name(), live.quad,
				live.screen.pixelWidth(), live.screen.pixelHeight(), live.screen.keyboard()));
		}

		return targets;
	}

	private void render(ScreenSink sink) {
		if (screens.isEmpty()) {
			return;
		}

		String dimension = Compat.dimensionKey();

		for (Live live : screens.values()) {
			if (live.feed == null) {
				continue;
			}

			if (!live.screen.dimension().equals(dimension)) {
				if (!live.warnedDimension) {
					live.warnedDimension = true;
					LOGGER.info("Luna TV screen {} is in {}, we are in {}; not drawing it here",
						live.screen.name(), live.screen.dimension(), dimension);
				}

				continue;
			}

			Frame frame = live.feed.poll();

			if (frame != null) {
				live.texture.upload(frame.rgba(), frame.width(), frame.height());
				live.lastPts = frame.pts();
				live.shown++;
				frame.free();

				if (!live.announced) {
					live.announced = true;
					LOGGER.info("Luna TV screen {}: first frame on the wall, {}x{}",
						live.screen.name(), frame.width(), frame.height());
				}
			}

			sink.draw(live.texture, live.quad, live.screen.brightness(), live.screen.glow());

			// the pointer and the click ripples, on top of the picture they
			// belong to
			Feedback.draw(sink, live.screen.name(), live.quad);
		}
	}

	/** A screen, its feed and its texture. */
	private static final class Live {

		private TvScreen screen;
		private ScreenQuad quad;
		private VideoFeed feed;
		private ScreenSound sound;
		private final ScreenTexture texture;
		private long lastPts;

		/** Frames the renderer took; written on the render thread, read per second. */
		private volatile long shown;

		private long lastDecoded;
		private long lastDropped;
		private long lastBytes;
		private long lastShown;
		private boolean announced;
		private boolean warnedDimension;

		private Live(TvScreen screen) {
			this.screen = screen;
			this.quad = new ScreenQuad(screen);
			this.texture = new ScreenTexture(screen.name());
		}

		/** Whether a fresh description is the same screen in the same place. */
		private boolean matches(TvScreen other) {
			return screen.dimension().equals(other.dimension())
				&& screen.minX() == other.minX() && screen.minY() == other.minY()
				&& screen.minZ() == other.minZ() && screen.maxX() == other.maxX()
				&& screen.maxY() == other.maxY() && screen.maxZ() == other.maxZ()
				&& screen.facing().equals(other.facing())
				&& screen.running() == other.running()
				&& screen.frameWidth() == other.frameWidth()
				&& screen.frameHeight() == other.frameHeight();
		}

		/** Drops the stream and its picture, keeping the screen itself. */
		private void release() {
			if (feed != null) {
				feed.close();
				feed = null;
			}

			// the texture goes too: a wall nobody is near should not be holding
			// several megabytes of video memory for a picture that is not arriving
			texture.close();
			announced = false;
		}

		private void close() {
			release();
		}
	}
}
