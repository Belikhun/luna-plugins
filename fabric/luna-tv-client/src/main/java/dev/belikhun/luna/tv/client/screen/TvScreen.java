package dev.belikhun.luna.tv.client.screen;

/**
 * One screen as the server described it.
 *
 * The corners are block positions and the facing is the side the picture is on,
 * exactly as the server stores them, so the quad drawn here lands on the same
 * plane the map version occupies for players without the mod.
 */
public final class TvScreen {

	private final String name;
	private final String world;
	private final String dimension;
	private final int ax;
	private final int ay;
	private final int az;
	private final int bx;
	private final int by;
	private final int bz;
	private final String facing;
	private final int pixelWidth;
	private final int pixelHeight;
	private final int frameWidth;
	private final int frameHeight;
	private final boolean running;
	private final boolean audio;
	private final boolean stereo;
	private final int audioRange;
	private final int volume;
	private final boolean locked;
	private final int brightness;
	private final int glow;
	private final boolean keyboard;
	private final boolean spatialAudio;

	public TvScreen(
		String name,
		String world,
		String dimension,
		int ax,
		int ay,
		int az,
		int bx,
		int by,
		int bz,
		String facing,
		int pixelWidth,
		int pixelHeight,
		int frameWidth,
		int frameHeight,
		boolean running,
		boolean audio,
		boolean stereo,
		int audioRange,
		int volume,
		boolean locked,
		int brightness,
		int glow,
		boolean keyboard,
		boolean spatialAudio
	) {
		this.name = name;
		this.world = world;
		this.dimension = dimension;
		this.ax = ax;
		this.ay = ay;
		this.az = az;
		this.bx = bx;
		this.by = by;
		this.bz = bz;
		this.facing = facing;
		this.pixelWidth = pixelWidth;
		this.pixelHeight = pixelHeight;
		this.frameWidth = frameWidth;
		this.frameHeight = frameHeight;
		this.running = running;
		this.audio = audio;
		this.stereo = stereo;
		this.audioRange = audioRange;
		this.volume = volume;
		this.locked = locked;
		this.brightness = brightness;
		this.glow = glow;
		this.keyboard = keyboard;
		this.spatialAudio = spatialAudio;
	}

	/** Whether the sound runs the speaker-pair model; direct playback when false. */
	public boolean spatialAudio() {
		return spatialAudio;
	}

	public String name() {
		return name;
	}

	/** The server's own name for the world, for messages only. */
	public String world() {
		return world;
	}

	/**
	 * The dimension's registry key, as the client also knows it.
	 *
	 * Sent separately from the world name because the two are unrelated strings:
	 * the server calls this world "survival" and the client calls the same place
	 * "minecraft:overworld", so matching on the name never succeeds.
	 */
	public String dimension() {
		return dimension;
	}

	public String facing() {
		return facing;
	}

	/** The wall's own grid: maps across times 128. */
	public int pixelWidth() {
		return pixelWidth;
	}

	public int pixelHeight() {
		return pixelHeight;
	}

	/** What the frames actually arrive at; equal to the wall size at scale 1. */
	public int frameWidth() {
		return frameWidth;
	}

	public int frameHeight() {
		return frameHeight;
	}

	public boolean running() {
		return running;
	}

	public boolean audio() {
		return audio;
	}

	/**
	 * Whether this screen's sound arrives as two interleaved channels.
	 *
	 * The stream carries whatever the server captured, so the split has to be
	 * known before a byte of it is played: two channels placed at the wall's
	 * edges, or one at its middle.
	 */
	public boolean stereo() {
		return stereo;
	}

	/**
	 * How far this screen can be heard from, in blocks.
	 *
	 * The same number voice chat is given for the players who are not running
	 * the mod, so a screen goes quiet at one distance rather than at two.
	 */
	public int audioRange() {
		return audioRange;
	}

	public int volume() {
		return volume;
	}

	public boolean locked() {
		return locked;
	}

	/**
	 * Picture brightness as a percentage, 100 being the page's own colours.
	 *
	 * Applied here rather than on the server: the server bakes its tone table
	 * into the pixels it dithers for the map path, which happens after the JPEG
	 * the stream carries, so a streamed frame arrives untouched.
	 */
	public int brightness() {
		return brightness;
	}

	/**
	 * How strongly the picture blooms, as a percentage; 0 is off.
	 *
	 * A MapEngine wall is drawn by Minecraft's own map renderer, so a shader pack
	 * treats it as map art and gives it the emissive look. A streamed screen goes
	 * straight to the framebuffer and gets none of that, so the halo is faked
	 * here instead.
	 */
	public int glow() {
		return glow;
	}

	/**
	 * Whether the page has a text field focused.
	 *
	 * When it does, the keyboard belongs to the screen without anybody asking for
	 * it: clicking a search box and then typing is the whole gesture, and a mode
	 * key in the middle of it is a mode key nobody remembers to press.
	 */
	public boolean keyboard() {
		return keyboard;
	}

	public int minX() {
		return Math.min(ax, bx);
	}

	public int minY() {
		return Math.min(ay, by);
	}

	public int minZ() {
		return Math.min(az, bz);
	}

	public int maxX() {
		return Math.max(ax, bx);
	}

	public int maxY() {
		return Math.max(ay, by);
	}

	public int maxZ() {
		return Math.max(az, bz);
	}

	/**
	 * Whether this screen faces along an axis the picture can be drawn on.
	 *
	 * @return true for the four compass faces and the two vertical ones
	 */
	public boolean flat() {
		return switch (facing) {
			case "NORTH", "SOUTH" -> minZ() == maxZ();
			case "EAST", "WEST" -> minX() == maxX();
			case "UP", "DOWN" -> minY() == maxY();
			default -> false;
		};
	}
}
