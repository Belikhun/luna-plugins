package dev.belikhun.luna.tv.client.sound;

import java.util.ArrayDeque;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One screen playing through the game's own OpenAL device.
 *
 * The context belongs to Minecraft, which makes it current for the whole process
 * at startup, so nothing here opens a device of its own: a second device would
 * ignore the audio-device setting, survive the game muting itself, and mix
 * outside everything the player has already told the game about their speakers.
 *
 * Distance is worked out here rather than left to OpenAL. The distance model is
 * global state Minecraft owns and may change between versions, so the sources
 * are set to no rolloff at all and given a gain this class computes, which makes
 * the fade identical to the number the server also hands voice chat. Direction
 * is still OpenAL's: the sources are listener-relative unit vectors built from
 * the camera's own basis, so panning works without depending on what Minecraft
 * last told the listener.
 *
 * A stereo screen is two mono sources at the wall's edges, exactly as the
 * voice-chat path does it, because that is the arrangement that makes a screen
 * sound like it is in front of you rather than inside your head.
 */
public final class ScreenSound implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** Fixed by the capture on the server; nothing here resamples. */
	private static final int SAMPLE_RATE = 48_000;

	/** Buffers per channel: the queue below, plus what is in flight. */
	private static final int BUFFER_COUNT = 32;

	/** Blocks queued before playback starts, about 120ms of jitter margin. */
	private static final int PRIME_BLOCKS = 6;

	/** Blocks allowed to sit ahead of the needle: past this is latency, not safety. */
	private static final int MAX_AHEAD = 16;

	/**
	 * Below this many queued blocks, silence is fed in to keep the source alive.
	 *
	 * A page between videos sends nothing, the source runs dry, OpenAL stops it,
	 * and restarting costs the priming wait: sound came back noticeably late
	 * every time a paused video resumed. Feeding zeros through the quiet keeps
	 * the source in its playing state, so the first real block after a pause is
	 * heard the moment it arrives. The zeros also set the latency floor, which
	 * is why the level is two blocks and not the full queue.
	 */
	private static final int SILENCE_FLOOR = 2;

	/**
	 * The most a source may be turned up to cancel somebody else's duck.
	 *
	 * Minecraft keeps the master volume on the OpenAL listener, and focus-idling
	 * mods duck that listener when the game window is in the background. The
	 * television divides the duck back out (see place), and this caps how far,
	 * so a listener driven to near zero does not ask for a gain in the hundreds.
	 */
	private static final float MAX_COMPENSATION_GAIN = 4.0f;

	/** Samples per channel in an injected silence block: 20ms at 48kHz. */
	private static final int SILENCE_SAMPLES = 960;

	/**
	 * How much of the radius is played at full volume before the fade begins.
	 *
	 * Without a plateau a screen is already noticeably quiet while the viewer is
	 * still standing in front of it, because the useful viewing distance for a
	 * wall this size is a good fraction of the radius it can be heard over.
	 */
	private static final double FLAT_FRACTION = 0.15;

	/**
	 * How long OpenAL may keep refusing before the screen is given up on.
	 *
	 * Measured in time rather than in attempts, and generous, because the thing
	 * that provokes it is Minecraft rebuilding its sound engine during a
	 * resource reload, and a reload on a heavily modded client takes fifteen
	 * seconds. Counting attempts instead gave up after eight pumps, which at a
	 * ten-millisecond cadence is eighty milliseconds, so a perfectly ordinary
	 * reload silenced every screen for the rest of the session.
	 */
	private static final long REBUILD_GRACE_MS = 60_000L;

	/** How long to wait between rebuild attempts while it keeps refusing. */
	private static final long REBUILD_RETRY_MS = 1_000L;

	private final String name;
	private final AudioFeed feed;
	private final boolean stereo;
	private final Voice[] voices;

	/** Where each channel sounds from, as x, y, z per voice. */
	private volatile double[] points;

	private volatile int volume = 100;
	private volatile double range = 24.0;
	private volatile boolean closing;

	private boolean ready;
	private boolean broken;

	private short[] silence;

	/** When OpenAL first started refusing, or 0 while it is behaving. */
	private long refusingSince;

	/** When the next rebuild may be attempted. */
	private long retryAt;

	public ScreenSound(String name, java.util.function.Supplier<String> url, boolean stereo) {
		this.name = name;
		this.stereo = stereo;
		this.feed = new AudioFeed(name, url);
		this.voices = new Voice[stereo ? 2 : 1];
	}

	/** Whether this screen's sound arrives as two interleaved channels. */
	public boolean stereo() {
		return stereo;
	}

	/** Starts fetching; the sources are opened by the first pump. */
	public void start() {
		feed.start();
	}

	/**
	 * Tells the mixer where the screen is and how loud it should be.
	 *
	 * Called from the client tick, which is the only thread that may look at the
	 * world; the mixer thread reads the snapshot and never the world itself.
	 *
	 * @param points x, y, z per channel: one point for mono, left then right for
	 *               stereo
	 * @param volume the screen's own volume, 0 to 100
	 * @param range how far it can be heard, in blocks
	 */
	public void aim(double[] points, int volume, int range) {
		this.points = points;
		this.volume = volume;
		this.range = Math.max(1, range);
	}

	/** Why the sound is unhappy, or null. */
	public String failure() {
		return feed.failure();
	}

	/** Blocks of audio waiting between the network and the speakers. */
	public int buffered() {
		return feed.depth();
	}

	@Override
	public void close() {
		closing = true;
		feed.close();
	}

	/**
	 * Moves one pump's worth of audio into OpenAL.
	 *
	 * Runs on the mixer thread and owns every OpenAL call this class makes, so
	 * the sources are created, fed and destroyed by one thread and none of it
	 * has to be defended against the game's own audio threads.
	 *
	 * @param listener the camera basis: position, then left, up and forward
	 * @param master the player's own volume for this kind of sound, 0 to 1
	 * @return false once the sound is finished with and may be forgotten
	 */
	boolean pump(double[] listener, float master) {
		if (!closing && !broken && !ready && System.currentTimeMillis() >= retryAt) {
			open();
		}

		if (closing || broken) {
			destroy();
			feed.close();

			return false;
		}

		if (!ready) {
			return true;
		}

		for (Voice voice : voices) {
			recycle(voice);
		}

		fill();
		place(listener, master);

		for (Voice voice : voices) {
			play(voice);
		}

		check();

		return true;
	}

	/**
	 * Notices the audio device being rebuilt underneath us.
	 *
	 * Minecraft destroys and recreates its OpenAL context whenever the player
	 * picks a different output device or reloads resources, and every name held
	 * here dies with it - silently, because OpenAL answers a dead name with an
	 * error rather than a fault. Building fresh sources is the whole recovery;
	 * the old ones are abandoned rather than deleted, since what they referred
	 * to no longer exists.
	 */
	private void check() {
		int error = AL10.alGetError();

		if (error == AL10.AL_NO_ERROR) {
			refusingSince = 0L;

			return;
		}

		long now = System.currentTimeMillis();

		if (refusingSince == 0L) {
			refusingSince = now;
			LOGGER.info("Luna TV {}: OpenAL refused (0x{}), rebuilding its sources",
				name, Integer.toHexString(error));
		}

		ready = false;

		for (int index = 0; index < voices.length; index++) {
			if (voices[index] != null) {
				voices[index].destroy();
			}

			voices[index] = null;
		}

		// the deletes above will have failed too when the names are dead, and
		// that error must not be read next time round as a fresh one
		AL10.alGetError();

		// Spaced out rather than hammered: while the game is between sound
		// engines every attempt fails, and a hundred a second would fill the log
		// with the same line for as long as the reload takes.
		retryAt = now + REBUILD_RETRY_MS;

		if (now - refusingSince < REBUILD_GRACE_MS) {
			return;
		}

		LOGGER.warn("Luna TV {}: OpenAL has refused for {} seconds, giving up on its sound",
			name, (now - refusingSince) / 1000L);
		broken = true;
	}

	private void open() {
		try {
			AL.getCapabilities();
		} catch (Throwable unavailable) {
			// No device yet. Not fatal: the game opens one late and reopens it
			// whenever the player changes output, so this is a wait, not a verdict.
			retryAt = System.currentTimeMillis() + REBUILD_RETRY_MS;

			if (refusingSince == 0L) {
				refusingSince = System.currentTimeMillis();
				LOGGER.info("Luna TV {}: no OpenAL context yet ({}), waiting", name, unavailable);
			}

			return;
		}

		// somebody else's error would otherwise be read as ours a moment later
		AL10.alGetError();

		try {
			for (int index = 0; index < voices.length; index++) {
				voices[index] = new Voice();
			}
		} catch (Throwable failed) {
			LOGGER.warn("Luna TV {}: could not open its audio sources: {}", name, failed.toString());
			retryAt = System.currentTimeMillis() + REBUILD_RETRY_MS;

			return;
		}

		ready = true;
		refusingSince = 0L;
	}

	private void recycle(Voice voice) {
		int processed = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_PROCESSED);

		while (processed > 0) {
			voice.free.addLast(AL10.alSourceUnqueueBuffers(voice.source));
			processed--;
		}
	}

	private void fill() {
		int ahead = AL10.alGetSourcei(voices[0].source, AL10.AL_BUFFERS_QUEUED);

		while (ahead < MAX_AHEAD) {
			short[] block = feed.poll();

			if (block == null) {
				break;
			}

			submit(block);
			ahead++;
		}

		// Reached only while the queue above is full, which means this screen is
		// already a third of a second ahead of the speakers. Anything still
		// waiting behind that could only be played later still, and late is the
		// one thing a soundtrack beside a moving picture cannot be.
		feed.trim(2);

		// the quiet between videos: keep a playing source fed with zeros rather
		// than let it stop and need re-priming when the sound comes back
		if (ahead < SILENCE_FLOOR
			&& AL10.alGetSourcei(voices[0].source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
			submit(silenceBlock());
		}
	}

	/** One block of zeros, shaped like the blocks the server sends. */
	private short[] silenceBlock() {
		int samples = stereo ? SILENCE_SAMPLES * 2 : SILENCE_SAMPLES;

		if (silence == null || silence.length != samples) {
			silence = new short[samples];
		}

		return silence;
	}

	private void submit(short[] block) {
		if (!stereo) {
			queue(voices[0], block);

			return;
		}

		int frames = block.length / 2;

		for (int channel = 0; channel < 2; channel++) {
			Voice voice = voices[channel];
			short[] half = voice.scratch(frames);

			for (int index = 0; index < frames; index++) {
				half[index] = block[index * 2 + channel];
			}

			queue(voice, half);
		}
	}

	private void queue(Voice voice, short[] samples) {
		Integer buffer = voice.free.pollFirst();

		if (buffer == null) {
			return;
		}

		AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, samples, SAMPLE_RATE);
		AL10.alSourceQueueBuffers(voice.source, buffer);
	}

	private void play(Voice voice) {
		if (AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
			return;
		}

		// Starting on the first block would mean restarting on every hiccup, and a
		// restart is a click. Waiting for a few is what turns network jitter into
		// nothing at all.
		if (AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED) >= PRIME_BLOCKS) {
			AL10.alSourcePlay(voice.source);
		}
	}

	private void place(double[] listener, float master) {
		double[] at = points;

		if (listener == null || at == null || at.length < voices.length * 3) {
			return;
		}

		for (int index = 0; index < voices.length; index++) {
			double dx = at[index * 3] - listener[0];
			double dy = at[index * 3 + 1] - listener[1];
			double dz = at[index * 3 + 2] - listener[2];
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

			// OpenAL's axes seen from the listener: +X right, +Y up, -Z forward.
			// The camera hands us left and forward, so two of the three flip.
			double right = -(dx * listener[3] + dy * listener[4] + dz * listener[5]);
			double up = dx * listener[6] + dy * listener[7] + dz * listener[8];
			double back = -(dx * listener[9] + dy * listener[10] + dz * listener[11]);
			double length = Math.sqrt(right * right + up * up + back * back);

			if (length < 1.0e-6) {
				// standing exactly on the speaker: put it straight ahead rather
				// than let the mixer divide by nothing
				right = 0.0;
				up = 0.0;
				back = -1.0;
			} else {
				right /= length;
				up /= length;
				back /= length;
			}

			// What should come out of the speakers. `master` is the player's own
			// sliders read raw from the options; the OpenAL listener gain also
			// carries the master volume (that is how Minecraft applies it), so
			// left alone it would be applied twice - and it is also where
			// focus-idling mods duck everything when the window goes to the
			// background, which a television should play straight through. So
			// the listener's contribution is divided back out, capped, and what
			// remains is exactly the volume the sliders ask for.
			double desired = master * volume / 100.0 * falloff(distance);
			float listenerGain = AL10.alGetListenerf(AL10.AL_GAIN);
			float gain = listenerGain > 1.0e-4f
				? (float) Math.min(desired / listenerGain, MAX_COMPENSATION_GAIN)
				: 0.0f;

			AL10.alSource3f(voices[index].source, AL10.AL_POSITION,
				(float) right, (float) up, (float) back);
			AL10.alSourcef(voices[index].source, AL10.AL_GAIN, gain);
		}
	}

	private double falloff(double distance) {
		double reach = range;
		double flat = reach * FLAT_FRACTION;

		if (distance <= flat) {
			return 1.0;
		}

		if (distance >= reach) {
			return 0.0;
		}

		return (reach - distance) / (reach - flat);
	}

	private void destroy() {
		if (!ready) {
			return;
		}

		ready = false;

		for (Voice voice : voices) {
			if (voice != null) {
				voice.destroy();
			}
		}
	}

	/** One mono channel: its OpenAL source and the buffers it cycles through. */
	private static final class Voice {

		private final int source;
		private final int[] buffers = new int[BUFFER_COUNT];
		private final ArrayDeque<Integer> free = new ArrayDeque<>(BUFFER_COUNT);

		private short[] scratch = new short[0];

		private Voice() {
			source = AL10.alGenSources();

			for (int index = 0; index < buffers.length; index++) {
				buffers[index] = AL10.alGenBuffers();
				free.addLast(buffers[index]);
			}

			AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
			AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
			AL10.alSourcef(source, AL10.AL_PITCH, 1.0f);
			AL10.alSourcef(source, AL10.AL_GAIN, 0.0f);

			// every distance model reduces to unity gain at zero rolloff, so this
			// one line makes the fade ours whatever Minecraft picked
			AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0f);

			// sources clamp their gain to 1.0 by default, which would silently
			// swallow the duck compensation computed in place()
			AL10.alSourcef(source, AL10.AL_MAX_GAIN, MAX_COMPENSATION_GAIN);
		}

		/** A right-sized staging array for one channel of a stereo block. */
		private short[] scratch(int frames) {
			if (scratch.length != frames) {
				scratch = new short[frames];
			}

			return scratch;
		}

		private void destroy() {
			AL10.alSourceStop(source);
			// no guard on any of these: OpenAL answers a name it no longer knows
			// with an error, not an exception, and the caller drains it
			// detaching first: a buffer still queued on a source cannot be deleted,
			// and OpenAL says so with an error rather than by waiting
			AL10.alSourcei(source, AL10.AL_BUFFER, 0);
			AL10.alDeleteSources(source);

			for (int buffer : buffers) {
				AL10.alDeleteBuffers(buffer);
			}
		}
	}
}
