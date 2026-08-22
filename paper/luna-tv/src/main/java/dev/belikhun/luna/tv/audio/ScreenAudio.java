package dev.belikhun.luna.tv.audio;

import java.util.UUID;

import org.bukkit.Location;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
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
 *
 * A voice-chat channel is mono by contract, so stereo is done with two of them:
 * one placed at each end of the wall, fed the matching half of the recording.
 * The game's own positional mixing then does the separation, which is why the
 * effect only exists for a listener standing in front of the screen - exactly
 * where somebody watching it is.
 */
public final class ScreenAudio {

	private static final short[] SILENCE = new short[ParecCapture.FRAME_SAMPLES];

	private final LunaLogger logger;
	private final String screenName;
	private final String sink;
	private final ParecCapture capture;
	private final boolean stereo;
	private final short[] scratch = new short[ParecCapture.FRAME_SAMPLES];

	/** Interleaved frame taken from the ring, stereo only. */
	private final short[] stereoScratch;

	/**
	 * The right channel's last two frames, written by the left supplier and
	 * read by the right one.
	 *
	 * Two buffers alternating, so the reader is never looking at the array the
	 * writer is filling.
	 */
	private final short[][] rightBuffers;
	private final java.util.concurrent.atomic.AtomicReference<short[]> rightFrame =
		new java.util.concurrent.atomic.AtomicReference<>();

	private int rightBufferIndex;

	private volatile TvConfig config;
	private volatile VoicechatServerApi api;
	private volatile LocationalAudioChannel channel;
	private volatile AudioPlayer player;
	private volatile LocationalAudioChannel rightChannel;
	private volatile AudioPlayer rightPlayer;
	private volatile int volume = 100;

	public ScreenAudio(LunaLogger logger, TvConfig config, String screenName, String sink, boolean stereo) {
		this.logger = logger;
		this.config = config;
		this.screenName = screenName;
		this.sink = sink;
		this.stereo = stereo;
		this.capture = new ParecCapture(logger, config, screenName, sink, stereo);
		this.stereoScratch = stereo ? new short[ParecCapture.FRAME_SAMPLES * 2] : null;
		this.rightBuffers = stereo
			? new short[][] {
				new short[ParecCapture.FRAME_SAMPLES],
				new short[ParecCapture.FRAME_SAMPLES],
			}
			: null;
	}

	/** Whether this stream carries two positioned channels. */
	public boolean stereo() {
		return stereo;
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
	public boolean start(VoicechatServerApi api, Location at, Location rightAt, int volumePercent) {
		if (player != null) {
			updateLocation(at, rightAt);

			return true;
		}

		this.volume = Math.max(0, Math.min(100, volumePercent));
		this.api = api;

		LocationalAudioChannel opened = open(api, at);

		if (opened == null) {
			logger.warn("Voice chat chưa chạy, không mở được kênh cho '" + screenName + "'.");

			return false;
		}

		LocationalAudioChannel openedRight = null;

		if (stereo) {
			openedRight = open(api, rightAt == null ? at : rightAt);

			if (openedRight == null) {
				logger.warn("Không mở được kênh phải cho '" + screenName + "', chạy một kênh.");
			}
		}

		capture.start();

		channel = opened;
		player = play(api, opened, this::nextFrame);

		if (openedRight != null) {
			rightChannel = openedRight;
			rightPlayer = play(api, openedRight, this::nextRightFrame);
		}

		return true;
	}

	private LocationalAudioChannel open(VoicechatServerApi api, Location at) {
		LocationalAudioChannel opened = api.createLocationalAudioChannel(
			UUID.randomUUID(),
			api.fromServerLevel(at.getWorld()),
			api.createPosition(at.getX(), at.getY(), at.getZ()));

		if (opened == null) {
			return null;
		}

		opened.setDistance(config.audioDistance());
		opened.setCategory(LunaTvVoicechatPlugin.CATEGORY_ID);

		return opened;
	}

	private AudioPlayer play(
		VoicechatServerApi api,
		LocationalAudioChannel on,
		java.util.function.Supplier<short[]> frames
	) {
		AudioPlayer started = api.createAudioPlayer(on, encoder(api), frames);

		started.startPlaying();

		return started;
	}

	/**
	 * The encoder a channel gets: ours when it can be built, else voice chat's.
	 *
	 * AUDIO rather than the VOIP default in the fallback: this is music and
	 * speech from a page, not a microphone, and VOIP mode is tuned to discard
	 * exactly that content.
	 */
	private OpusEncoder encoder(VoicechatServerApi api) {
		if (config.audioBitrate() > 0) {
			OpusEncoder tuned = HighQualityEncoder.create(logger, config.audioBitrate());

			if (tuned != null) {
				return tuned;
			}
		}

		return api.createEncoder(OpusEncoderMode.AUDIO);
	}

	/**
	 * The left channel, and the clock for both.
	 *
	 * In stereo this is the only reader of the ring: it takes one interleaved
	 * frame, splits it, and hands the right half over. Two readers is what let
	 * the channels drift apart, because each dropped its own frames when its
	 * voice-chat thread fell behind and neither could ever catch up again.
	 */
	private short[] nextFrame() {
		if (!stereo) {
			if (!capture.poll(scratch)) {
				return SILENCE;
			}

			return scaled(scratch);
		}

		if (!capture.poll(stereoScratch)) {
			// both sides go quiet together, which is the whole point of one ring
			rightFrame.set(null);

			return SILENCE;
		}

		short[] right = rightBuffers[rightBufferIndex];

		rightBufferIndex = (rightBufferIndex + 1) % rightBuffers.length;

		for (int index = 0; index < ParecCapture.FRAME_SAMPLES; index++) {
			scratch[index] = stereoScratch[index * 2];
			right[index] = stereoScratch[index * 2 + 1];
		}

		// the return value matters: at zero volume scaled() hands back the shared
		// silence buffer rather than touching the frame
		rightFrame.set(scaled(right));

		return scaled(scratch);
	}

	/**
	 * The right channel, which follows the left rather than keeping its own
	 * place in the stream.
	 *
	 * Voice chat drives the two suppliers on separate threads, so this one can
	 * be asked a moment before the left has published the current frame; it
	 * then repeats the previous one and is back in step on the next tick. The
	 * error is bounded at a single 20ms frame and corrects itself, which is
	 * what a second reader of the ring could not do.
	 */
	private short[] nextRightFrame() {
		short[] latest = rightFrame.get();

		return latest == null ? SILENCE : latest;
	}

	private short[] scaled(short[] frame) {
		int level = volume;

		if (level >= 100) {
			return frame;
		}

		if (level <= 0) {
			return SILENCE;
		}

		for (int index = 0; index < frame.length; index++) {
			frame[index] = (short) (frame[index] * level / 100);
		}

		return frame;
	}

	/**
	 * Moves the sound with the screen.
	 *
	 * @param at where the left channel (or the only one) should sound from
	 * @param rightAt where the right channel should sound from, in stereo
	 */
	public void updateLocation(Location at, Location rightAt) {
		VoicechatServerApi current = api;

		if (current == null) {
			return;
		}

		LocationalAudioChannel left = channel;

		if (left != null) {
			// the API's own Position, not one of ours: voice chat casts the value
			// straight to its implementation type and throws on anything else
			left.updateLocation(current.createPosition(at.getX(), at.getY(), at.getZ()));
		}

		LocationalAudioChannel right = rightChannel;

		if (right != null && rightAt != null) {
			right.updateLocation(current.createPosition(rightAt.getX(), rightAt.getY(), rightAt.getZ()));
		}
	}

	/**
	 * Sets playback volume.
	 *
	 * @param volumePercent 0 to 100
	 */
	public void volume(int volumePercent) {
		this.volume = Math.max(0, Math.min(100, volumePercent));
	}

	/** Stops streaming and closes the channels, leaving the sink in place. */
	public void stop() {
		AudioPlayer current = player;
		AudioPlayer right = rightPlayer;

		player = null;
		channel = null;
		rightPlayer = null;
		rightChannel = null;

		if (current != null) {
			current.stopPlaying();
		}

		if (right != null) {
			right.stopPlaying();
		}

		rightFrame.set(null);
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
}
