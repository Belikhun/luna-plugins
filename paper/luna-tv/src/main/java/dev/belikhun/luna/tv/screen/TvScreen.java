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
