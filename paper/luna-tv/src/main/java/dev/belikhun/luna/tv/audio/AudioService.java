package dev.belikhun.luna.tv.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatServerApi;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;

/**
 * Audio for every screen: the voice-chat registration, the sinks, the streams.
 *
 * Audio is optional in a way rendering is not. Voice chat may be absent, its
 * server may be down, PulseAudio may not be running, and none of those should
 * stop a screen from showing a picture; each just means this service says no
 * and explains why.
 */
public final class AudioService {

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final PulseAudioManager pulse;
	private final Map<String, ScreenAudio> streams = new ConcurrentHashMap<>();

	private volatile TvConfig config;
	private volatile LunaTvVoicechatPlugin bridge;
	private volatile VoicechatServerApi api;
	private volatile boolean pulseReady;

	public AudioService(JavaPlugin plugin, LunaLogger logger, TvConfig config) {
		this.plugin = plugin;
		this.logger = logger;
		this.config = config;
		this.pulse = new PulseAudioManager(logger, config);
	}

	public void config(TvConfig config) {
		this.config = config;
		this.pulse.config(config);
		streams.values().forEach(stream -> stream.config(config));
	}

	/**
	 * Probes PulseAudio and registers with voice chat if it is installed.
	 *
	 * @param onApiReady run when the voice server comes up, so screens that
	 *                   wanted audio can claim it
	 */
	public void enable(Runnable onApiReady) {
		pulseReady = pulse.probe();

		if (!pulseReady && config.audioEnabled()) {
			logger.warn("Âm thanh không khả dụng: " + pulse.unavailableReason());
		}

		if (!plugin.getServer().getPluginManager().isPluginEnabled("voicechat")) {
			logger.audit("Không có Simple Voice Chat. Màn hình vẫn hiển thị, chỉ không có tiếng.");

			return;
		}

		BukkitVoicechatService service = plugin.getServer().getServicesManager()
			.load(BukkitVoicechatService.class);

		if (service == null) {
			logger.warn("Simple Voice Chat đang bật nhưng chưa cung cấp BukkitVoicechatService.");

			return;
		}

		bridge = new LunaTvVoicechatPlugin(logger, ready -> {
			api = ready;
			plugin.getServer().getScheduler().runTask(plugin, onApiReady);
		}, () -> {
			api = null;
			streams.values().forEach(ScreenAudio::stop);
		});

		service.registerPlugin(bridge);
	}

	/** Stops every stream and, unless configured otherwise, removes the sinks. */
	public void disable() {
		for (Map.Entry<String, ScreenAudio> entry : streams.entrySet()) {
			entry.getValue().stop();

			if (!config.keepSinksOnDisable()) {
				pulse.removeSink(entry.getKey());
			}
		}

		streams.clear();

		if (bridge != null) {
			plugin.getServer().getServicesManager().unregister(bridge);
			bridge = null;
		}
	}

	/** Whether audio could be started at all right now. */
	public boolean available() {
		return config.audioEnabled() && pulseReady && api != null;
	}

	/**
	 * Why audio is unavailable, phrased for an operator.
	 *
	 * @return the reason, or null when audio is available
	 */
	public String unavailableReason() {
		if (!config.audioEnabled()) {
			return "audio.enabled = false trong config.yml";
		}

		if (!pulseReady) {
			return "PulseAudio: " + pulse.unavailableReason();
		}

		if (api == null) {
			return "Simple Voice Chat chưa sẵn sàng";
		}

		return null;
	}

	/**
	 * The sink a screen's browser should play into.
	 *
	 * Called before the browser launches, because the sink has to exist for
	 * Chromium to be pointed at it.
	 *
	 * @param screenName the screen's name
	 * @return the sink name, or null when audio is unavailable
	 */
	public String prepareSink(String screenName) {
		if (!config.audioEnabled() || !pulseReady) {
			return null;
		}

		return pulse.ensureSink(screenName);
	}

	/**
	 * Starts streaming a screen's audio into voice chat.
	 *
	 * @param screenName the screen's name
	 * @param sink the sink its browser plays into
	 * @param at where the sound should come from (the left edge, in stereo)
	 * @param rightAt where the right channel sounds from, null for mono
	 * @param volume starting volume, 0 to 100
	 * @return true when the stream started
	 */
	public boolean start(String screenName, String sink, Location at, Location rightAt, int volume) {
		if (!available() || sink == null) {
			return false;
		}

		boolean stereo = rightAt != null;
		ScreenAudio existing = streams.get(screenName);

		// stereo is decided when the recorder is built (parec is asked for one
		// channel or two), so a change of mode means a new stream object
		if (existing != null && existing.stereo() != stereo) {
			existing.stop();
			streams.remove(screenName);
			existing = null;
		}

		ScreenAudio stream = existing != null
			? existing
			: streams.computeIfAbsent(screenName,
				name -> new ScreenAudio(logger, config, name, sink, stereo));

		return stream.start(api, at, rightAt, volume);
	}

	/**
	 * Stops a screen's stream, keeping its sink for a later restart.
	 *
	 * @param screenName the screen's name
	 */
	public void stop(String screenName) {
		ScreenAudio stream = streams.get(screenName);

		if (stream != null) {
			stream.stop();
		}
	}

	/**
	 * Drops a screen's audio entirely, including its sink.
	 *
	 * @param screenName the screen's name
	 */
	public void remove(String screenName) {
		ScreenAudio stream = streams.remove(screenName);

		if (stream != null) {
			stream.stop();
		}

		pulse.removeSink(screenName);
	}

	/**
	 * Sets a screen's volume.
	 *
	 * @param screenName the screen's name
	 * @param volume 0 to 100
	 */
	public void volume(String screenName, int volume) {
		ScreenAudio stream = streams.get(screenName);

		if (stream != null) {
			stream.volume(volume);
		}
	}

	/**
	 * Whether a screen is currently streaming sound.
	 *
	 * @param screenName the screen's name
	 * @return true when its audio player is running
	 */
	public boolean playing(String screenName) {
		ScreenAudio stream = streams.get(screenName);

		return stream != null && stream.playing();
	}

	/**
	 * A screen's capture failure, for the status command.
	 *
	 * @param screenName the screen's name
	 * @return the message, or null
	 */
	public String failure(String screenName) {
		ScreenAudio stream = streams.get(screenName);

		return stream == null ? null : stream.failure();
	}

	/**
	 * Whether a player will actually hear anything.
	 *
	 * Voice chat only reaches players running the mod, so a screen with sound is
	 * silent for everyone else and it is worth saying so once.
	 *
	 * @param player the player to check
	 * @return true when the player has the client mod connected
	 */
	public boolean canHear(Player player) {
		VoicechatServerApi current = api;

		if (current == null) {
			return false;
		}

		var connection = current.getConnectionOf(player.getUniqueId());

		return connection != null && connection.isInstalled() && !connection.isDisabled();
	}
}
