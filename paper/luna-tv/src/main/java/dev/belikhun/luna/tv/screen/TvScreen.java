package dev.belikhun.luna.tv.screen;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;

/**
 * A named screen as it is remembered on disk.
 *
 * Everything here survives a restart. The runtime objects that render it (the
 * MapEngine display, the browser, the audio channel) are rebuilt from these
 * fields on the way up, and are not part of this record.
 */
public final class TvScreen {

	private final String name;
	private final String world;
	private final BlockVector cornerA;
	private final BlockVector cornerB;
	private final BlockFace facing;
	private final String createdBy;
	private final long createdAt;

	private String url;
	private int volume;
	private boolean locked;
	private boolean audio;
	private int scale;
	private int fps;
	private int brightness;
	private String converter;
	private String ditherPattern;
	private boolean stereo;
	private boolean scroll;
	private int streamFps;
	private int streamMegabits;
	private int audioRange;
	private int maxMegabits;
	private int glow;
	private int quality;
	private String redstoneWorld;
	private BlockVector redstone;

	/**
	 * Whether mod clients render this screen's sound through the spatial
	 * speaker-pair model rather than playing the channels straight.
	 *
	 * Direct (false, the default) is the mix as mastered, faded by distance:
	 * right for music. Spatial mixes the wall's speakers into both ears by
	 * geometry: right for a cinema wall. Set outside the constructor the way
	 * redstone is, because it arrived later and the constructor is long
	 * enough.
	 */
	private boolean spatialAudio;

	public TvScreen(
		String name,
		String world,
		BlockVector cornerA,
		BlockVector cornerB,
		BlockFace facing,
		String url,
		int volume,
		boolean locked,
		boolean audio,
		int scale,
		int fps,
		int maxMegabits,
		int brightness,
		int glow,
		int quality,
		String converter,
		String ditherPattern,
		boolean stereo,
		boolean scroll,
		int streamFps,
		int streamMegabits,
		int audioRange,
		String createdBy,
		long createdAt
	) {
		this.name = name;
		this.world = world;
		this.cornerA = cornerA;
		this.cornerB = cornerB;
		this.facing = facing;
		this.url = url;
		this.volume = clampVolume(volume);
		this.locked = locked;
		this.audio = audio;
		this.scale = clampScale(scale);
		this.fps = clampFps(fps);
		this.maxMegabits = clampMegabits(maxMegabits);
		this.brightness = clampBrightness(brightness);
		this.glow = clampGlow(glow);
		this.quality = clampQuality(quality);
		this.converter = normalizeConverter(converter);
		this.ditherPattern = normalizePattern(ditherPattern);
		this.stereo = stereo;
		this.scroll = scroll;
		this.streamFps = clampStreamFps(streamFps);
		this.streamMegabits = clampMegabits(streamMegabits);
		this.audioRange = clampAudioRange(audioRange);
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	/**
	 * Clamps a volume percentage into range.
	 *
	 * Up to 200 rather than 100, because the web is not mastered evenly: a
	 * quiet video on a screen already at full volume used to have no remedy.
	 * Past 100 the audio paths amplify and hard-clip where a sample leaves
	 * range, which is what an amplifier does.
	 *
	 * @param value the requested percentage
	 * @return the value, held to 0..200
	 */
	public static int clampVolume(int value) {
		return Math.max(0, Math.min(200, value));
	}

	/**
	 * Clamps a capture divisor into range.
	 *
	 * @param value the requested divisor
	 * @return the value, held to 1..4
	 */
	public static int clampScale(int value) {
		return Math.max(1, Math.min(4, value));
	}

	/**
	 * Clamps a per-screen frame rate; zero means "follow render.fps".
	 *
	 * @param value the requested rate
	 * @return the value, held to 0..30
	 */
	public static int clampFps(int value) {
		return Math.max(0, Math.min(30, value));
	}

	/**
	 * Clamps a per-screen bandwidth budget; zero means "follow render.max-megabits".
	 *
	 * @param value the requested megabits per second
	 * @return the value, held to 0..1000
	 */
	/**
	 * Clamps a brightness percentage; 100 leaves the picture untouched.
	 *
	 * @param value the requested percentage
	 * @return the value, held to 50..200
	 */
	/**
	 * Normalises a dither mode; anything unrecognised means "follow the config".
	 *
	 * @param value the requested mode
	 * @return DIRECT, ORDERED, FLOYD_STEINBERG, or an empty string for the default
	 */
	public static String normalizeConverter(String value) {
		if (value == null) {
			return "";
		}

		String upper = value.trim().toUpperCase(java.util.Locale.ROOT);

		return switch (upper) {
			case "DIRECT", "OFF" -> "DIRECT";
			case "ORDERED", "ON" -> "ORDERED";
			case "FLOYD_STEINBERG", "FLOYD" -> "FLOYD_STEINBERG";
			default -> "";
		};
	}

	/**
	 * Normalises an ordered-dither pattern; anything unrecognised means
	 * "follow the config".
	 *
	 * @param value the requested pattern
	 * @return bayer, a1..a4, or an empty string for the default
	 */
	public static String normalizePattern(String value) {
		if (value == null) {
			return "";
		}

		String lower = value.trim().toLowerCase(java.util.Locale.ROOT);

		return switch (lower) {
			case "bayer", "a1", "a2", "a3", "a4" -> lower;
			default -> "";
		};
	}

	public static int clampBrightness(int value) {
		return Math.max(50, Math.min(200, value == 0 ? 100 : value));
	}

	/**
	 * Clamps a glow strength; 0 is off, 100 a strong halo.
	 *
	 * Unlike brightness, 0 is a real value here rather than "unset", because a
	 * screen with no glow is a perfectly ordinary thing to want.
	 */
	public static int clampGlow(int value) {
		return Math.max(0, Math.min(200, value));
	}

	/** Clamps a JPEG quality; 0 means follow render.quality. */
	public static int clampQuality(int value) {
		return value <= 0 ? 0 : Math.min(100, value);
	}

	public static int clampMegabits(int value) {
		return Math.max(0, Math.min(1000, value));
	}

	public String name() {
		return name;
	}

	public String world() {
		return world;
	}

	public BlockVector cornerA() {
		return cornerA;
	}

	public BlockVector cornerB() {
		return cornerB;
	}

	public BlockFace facing() {
		return facing;
	}

	public String url() {
		return url;
	}

	public void url(String url) {
		this.url = url;
	}

	public int volume() {
		return volume;
	}

	public void volume(int volume) {
		this.volume = clampVolume(volume);
	}

	public boolean locked() {
		return locked;
	}

	public void locked(boolean locked) {
		this.locked = locked;
	}

	/** Whether this screen's audio is being streamed to voice chat. */
	public boolean audio() {
		return audio;
	}

	public void audio(boolean audio) {
		this.audio = audio;
	}

	/**
	 * Capture divisor: 1 renders the page at the wall's full pixel size, 2 at
	 * half. Higher is cheaper (video sites pick smaller streams and Chromium
	 * encodes less) at the cost of sharpness the map palette mostly hides.
	 */
	public int scale() {
		return scale;
	}

	public void scale(int scale) {
		this.scale = clampScale(scale);
	}

	/** Per-screen frame rate; 0 follows the global render.fps. */
	public int fps() {
		return fps;
	}

	public void fps(int fps) {
		this.fps = clampFps(fps);
	}

	/**
	 * Picture brightness as a percentage, 100 being the page's own colours.
	 *
	 * The map palette is 143 colours with a narrow range, so dark content loses
	 * most of its detail once quantised; lifting it before conversion is what
	 * gets that detail back.
	 */
	public int brightness() {
		return brightness;
	}

	public void brightness(int brightness) {
		this.brightness = clampBrightness(brightness);
	}

	/**
	 * How strongly the picture blooms into its surroundings, as a percentage.
	 *
	 * Faked, and deliberately so: a MapEngine wall is drawn by Minecraft's own
	 * map renderer, so it picks up the emissive treatment a shader pack gives
	 * map art. A streamed screen is drawn straight to the framebuffer and gets
	 * none of that, so the halo is reproduced in the client's own shader.
	 */
	public int glow() {
		return glow;
	}

	public void glow(int glow) {
		this.glow = clampGlow(glow);
	}

	/**
	 * This screen's own JPEG quality, or 0 to follow render.quality.
	 *
	 * Per screen because the cost is per screen: each screen has its own Chromium
	 * encoding its own frames, and a small wall in a corner does not deserve the
	 * same bitrate as a cinema-sized one.
	 */
	public int quality() {
		return quality;
	}

	public void quality(int quality) {
		this.quality = clampQuality(quality);
	}

	/**
	 * Per-screen dither mode; empty follows render.converter.
	 *
	 * DIRECT takes the nearest palette colour, which keeps flat areas and text
	 * clean. ORDERED dithers positionally inside the decode pass (see
	 * {@link #ditherPattern()}), near DIRECT's cost. FLOYD_STEINBERG is
	 * MapEngine's error-diffusion dither, the smoothest gradients but by far
	 * the most CPU per frame.
	 */
	public String converter() {
		return converter;
	}

	public void converter(String converter) {
		this.converter = normalizeConverter(converter);
	}

	/**
	 * Per-screen ordered-dither pattern; empty follows render.ordered-pattern.
	 *
	 * Only read while the dither mode resolves to ORDERED.
	 */
	public String ditherPattern() {
		return ditherPattern;
	}

	public void ditherPattern(String ditherPattern) {
		this.ditherPattern = normalizePattern(ditherPattern);
	}

	/**
	 * Whether this screen's sound is split into two positioned channels.
	 *
	 * A voice-chat channel is mono, so stereo means one channel at each end of
	 * the wall and the game's positional mixing doing the separation.
	 */
	public boolean stereo() {
		return stereo;
	}

	public void stereo(boolean stereo) {
		this.stereo = stereo;
	}

	/** Whether mod clients spatialize this screen's sound; direct when false. */
	public boolean spatialAudio() {
		return spatialAudio;
	}

	public void spatialAudio(boolean spatialAudio) {
		this.spatialAudio = spatialAudio;
	}

	/**
	 * Whether the mouse wheel scrolls this screen's page.
	 *
	 * Off leaves the hotbar alone entirely, which is what somebody who keeps
	 * their tools in order and only wants to watch the screen wants.
	 */
	public boolean scroll() {
		return scroll;
	}

	public void scroll(boolean scroll) {
		this.scroll = scroll;
	}

	/** Clamps a stream frame rate; 0 means "follow stream.fps". */
	public static int clampStreamFps(int value) {
		return Math.max(0, Math.min(60, value));
	}

	/**
	 * Per-screen frame rate for the client stream; 0 follows stream.fps.
	 *
	 * Separate from {@link #fps()}, which paces the map wall: the two audiences
	 * are limited by completely different things.
	 */
	public int streamFps() {
		return streamFps;
	}

	public void streamFps(int streamFps) {
		this.streamFps = clampStreamFps(streamFps);
	}

	/** Per-viewer stream ceiling in megabits; 0 follows stream.max-megabits. */
	public int streamMegabits() {
		return streamMegabits;
	}

	public void streamMegabits(int streamMegabits) {
		this.streamMegabits = clampMegabits(streamMegabits);
	}

	/**
	 * Clamps a hearing radius; 0 means "follow audio.distance".
	 *
	 * The ceiling is generous on purpose. A cinema wall is built to be watched
	 * from a long way back, and a radius that stops short of where the picture
	 * is still legible is the one thing an operator cannot work around.
	 *
	 * @param value the requested radius in blocks
	 * @return the value, held to 0..256
	 */
	public static int clampAudioRange(int value) {
		return Math.max(0, Math.min(256, value));
	}

	/**
	 * How far this screen can be heard from, in blocks; 0 follows audio.distance.
	 *
	 * One number for both audiences. Voice chat gets it as the channel's own
	 * distance, and the client mod gets it in the registry and fades its own
	 * sources over it, so walking away from a screen sounds the same whether or
	 * not the listener is running the mod.
	 */
	public int audioRange() {
		return audioRange;
	}

	public void audioRange(int audioRange) {
		this.audioRange = clampAudioRange(audioRange);
	}

	/** Per-screen bandwidth budget in megabits; 0 follows the global default. */
	public int maxMegabits() {
		return maxMegabits;
	}

	public void maxMegabits(int maxMegabits) {
		this.maxMegabits = clampMegabits(maxMegabits);
	}

	/** World of the linked redstone block, null when unlinked. */
	public String redstoneWorld() {
		return redstoneWorld;
	}

	/** Position of the linked redstone block, null when unlinked. */
	public BlockVector redstone() {
		return redstone;
	}

	/**
	 * Links (or clears, with nulls) the redstone block whose rising edge
	 * toggles this screen's power.
	 */
	public void redstone(String world, BlockVector position) {
		this.redstoneWorld = world;
		this.redstone = position;
	}

	public String createdBy() {
		return createdBy;
	}

	public long createdAt() {
		return createdAt;
	}

	/**
	 * Width of the screen in maps.
	 *
	 * Derived from the corner span along whichever axis the facing leaves free,
	 * so a wall built east-west and one built north-south both measure right.
	 *
	 * @return map count across
	 */
	public int mapsWide() {
		return switch (facing) {
			case NORTH, SOUTH -> span(cornerA.getBlockX(), cornerB.getBlockX());
			case EAST, WEST -> span(cornerA.getBlockZ(), cornerB.getBlockZ());
			default -> span(cornerA.getBlockX(), cornerB.getBlockX());
		};
	}

	/**
	 * Height of the screen in maps.
	 *
	 * @return map count down
	 */
	public int mapsHigh() {
		return switch (facing) {
			case UP, DOWN -> span(cornerA.getBlockZ(), cornerB.getBlockZ());
			default -> span(cornerA.getBlockY(), cornerB.getBlockY());
		};
	}

	/** Browser viewport width: 128 pixels per map. */
	public int pixelWidth() {
		return mapsWide() * 128;
	}

	/** Browser viewport height: 128 pixels per map. */
	public int pixelHeight() {
		return mapsHigh() * 128;
	}

	private static int span(int a, int b) {
		return Math.abs(a - b) + 1;
	}
}
