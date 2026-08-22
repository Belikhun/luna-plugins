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
	private int maxMegabits;
	private String redstoneWorld;
	private BlockVector redstone;

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
		String converter,
		String ditherPattern,
		boolean stereo,
		boolean scroll,
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
		this.converter = normalizeConverter(converter);
		this.ditherPattern = normalizePattern(ditherPattern);
		this.stereo = stereo;
		this.scroll = scroll;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	/**
	 * Clamps a volume percentage into range.
	 *
	 * @param value the requested percentage
	 * @return the value, held to 0..100
	 */
	public static int clampVolume(int value) {
		return Math.max(0, Math.min(100, value));
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
