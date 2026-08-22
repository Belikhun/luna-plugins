package dev.belikhun.luna.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.tv.audio.HighQualityEncoder;

/**
 * Typed snapshot of config.yml.
 *
 * Read once and handed around as an immutable value: a running screen must not
 * see half of a reload. {@link #reload()} builds a fresh snapshot instead of
 * mutating this one, and the plugin swaps the reference.
 */
public final class TvConfig {

	/** A one-click entry in the control panel's quick-page row. */
	public record Preset(String name, String url) {}

	private static final int FPS_MIN = 1;
	private static final int FPS_MAX = 30;

	private final int fps;
	private final int maxMegabits;
	private final boolean bundling;
	private final int captureScale;
	private final int brightness;
	private final int quality;
	private final String converter;
	private final String orderedPattern;
	private final int audioBitrate;
	private final int scrollStep;
	private final boolean invertScroll;
	private final double spawnDistance;
	private final double interactDistance;
	private final int maxScreens;
	private final String homepage;
	private final List<Preset> presets;
	private final String executable;
	private final String userAgent;
	private final int debugPortStart;
	private final int startupTimeoutSeconds;
	private final List<String> switches;
	private final boolean audioEnabled;
	private final String sinkPrefix;
	private final String pactlPath;
	private final String parecPath;
	private final float audioDistance;
	private final boolean keepSinksOnDisable;
	private final boolean debug;

	private TvConfig(ConfigStore store) {
		this.fps = clamp(store.get("render.fps").asInt(20), FPS_MIN, FPS_MAX);
		this.maxMegabits = clamp(store.get("render.max-megabits").asInt(36), 5, 1000);
		this.bundling = store.get("render.bundling").asBoolean(false);
		this.captureScale = clamp(store.get("render.capture-scale").asInt(1), 1, 4);
		this.brightness = clamp(store.get("render.brightness").asInt(100), 50, 200);
		this.quality = clamp(store.get("render.quality").asInt(60), 1, 100);
		this.converter = store.get("render.converter").asString("ORDERED");
		this.orderedPattern = normalizePattern(store.get("render.ordered-pattern").asString("a4"));
		this.audioBitrate = Math.max(0, Math.min(HighQualityEncoder.MAX_BITRATE,
			store.get("audio.bitrate").asInt(128_000)));
		this.scrollStep = Math.max(20, Math.min(1_000,
			store.get("input.scroll-step").asInt(120)));
		this.invertScroll = store.get("input.invert-scroll").asBoolean(false);
		this.spawnDistance = store.get("render.spawn-distance").asDouble(48.0);
		this.interactDistance = store.get("render.interact-distance").asDouble(6.0);

		this.maxScreens = Math.max(1, store.get("screens.max").asInt(4));
		this.homepage = store.get("screens.homepage").asString("https://www.google.com");
		this.presets = readPresets(store);

		this.executable = store.get("browser.executable").asString("/snap/bin/chromium");
		this.userAgent = store.get("browser.user-agent").asString(
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36");
		this.debugPortStart = clamp(store.get("browser.debug-port-start").asInt(39330), 1024, 65000);
		this.startupTimeoutSeconds = Math.max(1, store.get("browser.startup-timeout").asInt(20));
		this.switches = readStringList(store, "browser.switches");

		this.audioEnabled = store.get("audio.enabled").asBoolean(true);
		this.sinkPrefix = store.get("audio.sink-prefix").asString("lunatv");
		this.pactlPath = store.get("audio.pactl-path").asString("pactl");
		this.parecPath = store.get("audio.parec-path").asString("parec");
		this.audioDistance = (float) store.get("audio.distance").asDouble(24.0);
		this.keepSinksOnDisable = store.get("audio.keep-sinks-on-disable").asBoolean(false);

		this.debug = store.get("logging.debug").asBoolean(false);
	}

	/**
	 * Reads the store into a snapshot.
	 *
	 * @param store the plugin's config.yml
	 * @return an immutable snapshot of every setting
	 */
	public static TvConfig from(ConfigStore store) {
		return new TvConfig(store);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static List<String> readStringList(ConfigStore store, String path) {
		List<String> raw = store.raw().getStringList(path);

		return List.copyOf(raw);
	}

	private static List<Preset> readPresets(ConfigStore store) {
		List<Preset> presets = new ArrayList<>();

		for (Object entry : store.raw().getList("screens.presets", List.of())) {
			if (entry instanceof ConfigurationSection section) {
				addPreset(presets, section.getString("name"), section.getString("url"));
				continue;
			}

			// a list of maps is what snakeyaml hands back for this shape
			if (entry instanceof java.util.Map<?, ?> map) {
				addPreset(presets, asText(map.get("name")), asText(map.get("url")));
			}
		}

		return List.copyOf(presets);
	}

	private static void addPreset(List<Preset> presets, String name, String url) {
		if (name == null || name.isBlank() || url == null || url.isBlank()) {
			return;
		}

		presets.add(new Preset(name, url));
	}

	private static String asText(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	public int fps() {
		return fps;
	}

	/** Outgoing map-data budget per screen, in megabits per second. */
	public int maxMegabits() {
		return maxMegabits;
	}

	/** Whether frames are wrapped in packet bundles (a tearing/compat trade). */
	public boolean bundling() {
		return bundling;
	}

	/**
	 * Browser capture divisor: 2 captures at half size and upscales.
	 *
	 * JPEG-encoding the screencast is what caps a big screen's frame rate, and
	 * its cost falls with the square of this. The map palette erases most of
	 * the sharpness lost.
	 */
	public int captureScale() {
		return captureScale;
	}

	/** Default picture brightness for new screens, as a percentage. */
	public int brightness() {
		return brightness;
	}

	public int quality() {
		return quality;
	}

	public String converter() {
		return converter;
	}

	/** Which positional pattern the ORDERED dither mode uses. */
	public String orderedPattern() {
		return orderedPattern;
	}

	/**
	 * Opus bitrate in bits per second; 0 uses voice chat's own encoder.
	 *
	 * Voice chat's default lands near 51 kbps with FEC overhead on top, which
	 * is a speech budget. Music wants more.
	 */
	public int audioBitrate() {
		return audioBitrate;
	}

	/**
	 * Pixels the page scrolls for one notch of the mouse wheel.
	 *
	 * 120 is roughly what a desktop browser moves for one notch, so a page
	 * behaves the way its author expected.
	 */
	public int scrollStep() {
		return scrollStep;
	}

	/** Flips wheel direction, for a client whose wheel is set up the other way. */
	public boolean invertScroll() {
		return invertScroll;
	}

	private static String normalizePattern(String value) {
		String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

		return switch (lower) {
			case "bayer", "a1", "a2", "a3", "a4" -> lower;
			default -> "a4";
		};
	}

	public double spawnDistance() {
		return spawnDistance;
	}

	public double interactDistance() {
		return interactDistance;
	}

	public int maxScreens() {
		return maxScreens;
	}

	public String homepage() {
		return homepage;
	}

	public List<Preset> presets() {
		return presets;
	}

	public String executable() {
		return executable;
	}

	public String userAgent() {
		return userAgent;
	}

	public int debugPortStart() {
		return debugPortStart;
	}

	public int startupTimeoutSeconds() {
		return startupTimeoutSeconds;
	}

	public List<String> switches() {
		return switches;
	}

	public boolean audioEnabled() {
		return audioEnabled;
	}

	public String sinkPrefix() {
		return sinkPrefix;
	}

	public String pactlPath() {
		return pactlPath;
	}

	public String parecPath() {
		return parecPath;
	}

	public float audioDistance() {
		return audioDistance;
	}

	public boolean keepSinksOnDisable() {
		return keepSinksOnDisable;
	}

	public boolean debug() {
		return debug;
	}
}
