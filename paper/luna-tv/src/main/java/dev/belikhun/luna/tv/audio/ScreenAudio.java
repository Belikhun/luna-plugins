package dev.belikhun.luna.tv.audio;

import java.util.UUID;

import org.bukkit.Location;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;

/**
 * One screen's sound: its sink, its recorder, and its voice-chat channel.
 *
 * The audio player's supplier is the contract that matters. Voice chat asks for
 * exactly 960 samples every 20ms and ends the stream for good on a null, so a
 * gap in the recording must be answered with silence, never with nothing. That
 * is why an underrun here is not an error: a page between videos is silent, and
 * the channel should stay open across it.
 */
public final class ScreenAudio {

	private static final short[] SILENCE = new short[ParecCapture.FRAME_SAMPLES];

	private final LunaLogger logger;
	private final String screenName;
	private final String sink;
	private final ParecCapture capture;
	private final short[] scratch = new short[ParecCapture.FRAME_SAMPLES];

	private volatile TvConfig config;
	private volatile LocationalAudioChannel channel;
	private volatile AudioPlayer player;
	private volatile int volume = 100;

	public ScreenAudio(LunaLogger logger, TvConfig config, String screenName, String sink) {
		this.logger = logger;
		this.config = config;
		this.screenName = screenName;
		this.sink = sink;
		this.capture = new ParecCapture(logger, config, screenName, sink);
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	public String sink() {
		return sink;
	}

	/**
	 * Opens the channel and starts streaming this screen's audio.
	 *
	 * @param api the live voice-chat server API
	 * @param at where the sound comes from
	 * @param volumePercent starting volume, 0 to 100
	 * @return true when a channel was opened
	 */
	public boolean start(VoicechatServerApi api, Location at, int volumePercent) {
		if (player != null) {
			updateLocation(at);

			return true;
		}

		this.volume = Math.max(0, Math.min(100, volumePercent));

		LocationalAudioChannel opened = api.createLocationalAudioChannel(
			UUID.randomUUID(),
			api.fromServerLevel(at.getWorld()),
			api.createPosition(at.getX(), at.getY(), at.getZ()));

		if (opened == null) {
			logger.warn("Voice chat chưa chạy, không mở được kênh cho '" + screenName + "'.");

			return false;
		}

		opened.setDistance(config.audioDistance());
		opened.setCategory(LunaTvVoicechatPlugin.CATEGORY_ID);

		capture.start();

		// AUDIO rather than the VOIP default: this is music and speech from a page,
		// not a microphone, and VOIP mode is tuned to discard exactly that content
		AudioPlayer started = api.createAudioPlayer(
			opened, api.createEncoder(OpusEncoderMode.AUDIO), this::nextFrame);

		channel = opened;
		player = started;
		started.startPlaying();

		return true;
	}

	private short[] nextFrame() {
		if (!capture.poll(scratch)) {
			return SILENCE;
		}

		int level = volume;

		if (level >= 100) {
			return scratch;
		}

		if (level <= 0) {
			return SILENCE;
		}

		for (int index = 0; index < scratch.length; index++) {
			scratch[index] = (short) (scratch[index] * level / 100);
		}

		return scratch;
	}

	/**
	 * Moves the sound with the screen.
	 *
	 * @param at the new position
	 */
	public void updateLocation(Location at) {
		LocationalAudioChannel current = channel;

		if (current == null) {
			return;
		}

		current.updateLocation(new PositionOf(at.getX(), at.getY(), at.getZ()));
	}

	/**
	 * Sets playback volume.
	 *
	 * @param volumePercent 0 to 100
	 */
	public void volume(int volumePercent) {
		this.volume = Math.max(0, Math.min(100, volumePercent));
	}

	/** Stops streaming and closes the channel, leaving the sink in place. */
	public void stop() {
		AudioPlayer current = player;

		player = null;
		channel = null;

		if (current != null) {
			current.stopPlaying();
		}

		capture.stop();
	}

	public boolean playing() {
		AudioPlayer current = player;

		return current != null && current.isPlaying();
	}

	/** Why the recorder is unhappy, or null. */
	public String failure() {
		return capture.failure();
	}

	public boolean capturing() {
		return capture.alive();
	}

	/** A Position that does not need the API to build one. */
	private record PositionOf(double x, double y, double z) implements de.maxhenkel.voicechat.api.Position {

		@Override
		public double getX() {
			return x;
		}

		@Override
		public double getY() {
			return y;
		}

		@Override
		public double getZ() {
			return z;
		}
	}
}
