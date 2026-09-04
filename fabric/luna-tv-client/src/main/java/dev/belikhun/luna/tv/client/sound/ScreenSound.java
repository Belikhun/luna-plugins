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
 * Nothing spatial is left to OpenAL. The screen plays through one stereo
 * source, which OpenAL delivers to the output verbatim - a stereo source is
 * never spatialized - and what goes into it is one of two mixes. Direct mode,
 * the default, is the stream's own channels faded by distance and nothing
 * else: the television heard the way its mix was mastered. Spatial mode
 * models a pair of physical speakers - each heard by both ears, geometry
 * deciding balance and a small interaural delay - selected with
 * -Dlunatv.audio=spatial. Positional mono sources were the original
 * arrangement and were wrong in a way no tuning fixed: OpenAL pans a
 * hard-side source entirely into one ear, so facing an ear at the screen
 * muted the other channel outright, which no loudspeaker in a room has ever
 * done.
 */
public final class ScreenSound implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** Fixed by the capture on the server; nothing here resamples. */
	private static final int SAMPLE_RATE = 48_000;

	/** Buffers per channel: the queue below, plus what is in flight. */
	private static final int BUFFER_COUNT = 32;

	/**
	 * Blocks queued before playback starts, and the most allowed to sit ahead
	 * of the needle.
	 *
	 * Small on purpose, and the number is the stereo image's reaction time:
	 * the speaker mix is baked into a block when it is queued, so everything
	 * sitting in this queue answers a head turn with yesterday's geometry.
	 * Sixteen blocks ahead meant the image trailed the camera by up to a
	 * third of a second, which reads as broken rather than smooth. Network
	 * jitter is not this queue's job: the feed's own queue holds the arriving
	 * blocks unmixed, where geometry has not been decided yet, and the
	 * silence floor below covers the gaps.
	 */
	private static final int PRIME_BLOCKS = 3;

	/** Mixed blocks ahead of the needle: ~80ms, the image's worst-case lag. */
	private static final int MAX_AHEAD = 4;

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

	/**
	 * Overrides every screen's audio mode for this session, for debugging.
	 *
	 * The mode is normally the screen's own setting, carried by the registry
	 * and switchable live by an operator. "direct" or "spatial" here forces
	 * one rendering everywhere; empty (the default) lets each screen decide.
	 *
	 * Direct mode is a television heard the way its own mix was mastered:
	 * left channel to the left ear, right to the right, faded by distance and
	 * nothing else; because the fade rides on the source gain it reacts to
	 * movement instantly. Spatial mode is the speaker-pair model below.
	 */
	private static final String FORCED_MODE = System.getProperty("lunatv.audio", "");

	/**
	 * How far apart the two ears' shares may drift, 0 for mono and 1 for a
	 * hard pan.
	 *
	 * The speaker-pair mix: a speaker's sample reaches both ears at
	 * constant power, split by its lateral angle scaled by this. At 0.7 a
	 * speaker straight to one side plays the far ear about eight decibels
	 * down - clearly quieter, clearly still there - which is how a real
	 * speaker sounds from across the room, and nothing like the total
	 * one-ear silence a hard pan produced.
	 */
	private static final double SEPARATION = 0.7;

	/**
	 * How much the geometry's angles are widened before steering the mix.
	 *
	 * From a normal viewing spot the wall's speakers sit only fifteen or
	 * twenty degrees apart, and steering by the raw angle left the two
	 * channels two decibels apart, a mix indistinguishable from mono. Real
	 * speakers that far apart still image clearly because ears use timing
	 * and spectral cues that amplitude cannot carry, so the amplitude model
	 * compensates by widening: a speaker twenty degrees off centre steers
	 * most of the way toward its ear, and walking across the front of the
	 * wall sweeps the image naturally.
	 */
	private static final double WIDEN = 3.0;

	/**
	 * The far ear's largest lag behind the near one, in samples.
	 *
	 * The interaural time difference: about a quarter of a millisecond is
	 * what a head's width delays a sound arriving from the side, and it is
	 * the cue that places a source outside the head, where amplitude alone
	 * pans it around inside. Twelve samples at 48kHz. The delays slew a few
	 * samples per pump, so a head turn glides instead of clicking.
	 */
	private static final int MAX_ITD_SAMPLES = 12;

	private final String name;
	private final AudioFeed feed;
	private final boolean stereo;

	/** Per speaker, what share of it each ear receives; rebuilt every pump. */
	private final double[] earLeft = { 0.5, 0.5 };
	private final double[] earRight = { 0.5, 0.5 };

	/** Per speaker, each ear's delay in samples: current (slewed) and wanted. */
	private final double[] delayLeft = new double[2];
	private final double[] delayRight = new double[2];
	private final double[] delayLeftTarget = new double[2];
	private final double[] delayRightTarget = new double[2];

	/** Each speaker's previous-block tail, feeding the far ear's delay line. */
	private final short[][] history = {
		new short[MAX_ITD_SAMPLES],
		new short[MAX_ITD_SAMPLES],
	};

	private Voice voice;

	/** Where each channel sounds from, as x, y, z per speaker. */
	private volatile double[] points;

	private volatile int volume = 100;
	private volatile double range = 24.0;
	private volatile boolean spatial;
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
	 * @param volume the screen's own volume, 0 to 200; past 100 it amplifies,
	 *        inside the compensation cap the gain math already carries
	 * @param range how far it can be heard, in blocks
	 * @param spatial true for the speaker-pair model, false to play direct;
	 *        the screen's own setting, switchable live
	 */
	public void aim(double[] points, int volume, int range, boolean spatial) {
		this.points = points;
		this.volume = volume;
		this.range = Math.max(1, range);
		this.spatial = FORCED_MODE.isEmpty()
			? spatial
			: "spatial".equalsIgnoreCase(FORCED_MODE);
	}

	/** Whether this pump renders the speaker-pair model rather than direct. */
	private boolean direct() {
		return !spatial;
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

		recycle(voice);

		// gains first: the mix is baked into each block as it is queued, so a
		// block must be mixed with the geometry of the pump that queues it
		mixGains(listener, master);
		fill();
		play(voice);
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

		if (voice != null) {
			voice.destroy();
			voice = null;
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
			voice = new Voice();
		} catch (Throwable failed) {
			LOGGER.warn("Luna TV {}: could not open its audio sources: {}", name, failed.toString());
			retryAt = System.currentTimeMillis() + REBUILD_RETRY_MS;

			return;
		}

		ready = true;
		refusingSince = 0L;

		// names the audio path in the log: a report of positional symptoms
		// (one ear silent, muffled from behind) means this line is absent and
		// an older build or the voice-chat fallback is what is playing
		LOGGER.info("Luna TV {}: {} opened, {} wall into stereo source",
			name, direct() ? "direct player" : "speaker-pair mixer",
			stereo ? "stereo" : "mono");
	}

	private void recycle(Voice voice) {
		int processed = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_PROCESSED);

		while (processed > 0) {
			voice.free.addLast(AL10.alSourceUnqueueBuffers(voice.source));
			processed--;
		}
	}

	private void fill() {
		int ahead = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED);

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
			&& AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
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

	/**
	 * Mixes one block through the speaker pair and queues the result.
	 *
	 * Every sample of every speaker lands in both output channels, weighted by
	 * the ear shares mixGains computed this pump. Summing two speakers can
	 * exceed short range exactly the way two real speakers sum in a room; the
	 * peaks are hard-clipped, the same answer the server's amplifier gives.
	 */
	private void submit(short[] block) {
		if (direct()) {
			if (stereo) {
				// already interleaved the way the output wants it; alBufferData
				// copies, so handing the feed's own array over is safe
				queue(voice, block);

				return;
			}

			short[] doubled = voice.scratch(block.length * 2);

			for (int index = 0; index < block.length; index++) {
				doubled[index * 2] = block[index];
				doubled[index * 2 + 1] = block[index];
			}

			queue(voice, doubled);

			return;
		}

		if (stereo) {
			int frames = block.length / 2;
			short[] mixed = voice.scratch(block.length);
			int lagLeft0 = (int) Math.round(delayLeft[0]);
			int lagLeft1 = (int) Math.round(delayLeft[1]);
			int lagRight0 = (int) Math.round(delayRight[0]);
			int lagRight1 = (int) Math.round(delayRight[1]);

			for (int index = 0; index < frames; index++) {
				double left = past(block, 2, 0, index - lagLeft0) * earLeft[0]
					+ past(block, 2, 1, index - lagLeft1) * earLeft[1];
				double right = past(block, 2, 0, index - lagRight0) * earRight[0]
					+ past(block, 2, 1, index - lagRight1) * earRight[1];

				mixed[index * 2] = clip(left);
				mixed[index * 2 + 1] = clip(right);
			}

			remember(block, 2, frames);
			queue(voice, mixed);

			return;
		}

		short[] mixed = voice.scratch(block.length * 2);
		int lagLeft = (int) Math.round(delayLeft[0]);
		int lagRight = (int) Math.round(delayRight[0]);

		for (int index = 0; index < block.length; index++) {
			mixed[index * 2] = clip(past(block, 1, 0, index - lagLeft) * earLeft[0]);
			mixed[index * 2 + 1] = clip(past(block, 1, 0, index - lagRight) * earRight[0]);
		}

		remember(block, 1, block.length);
		queue(voice, mixed);
	}

	/** A speaker's sample, reaching back into the previous block's tail. */
	private double past(short[] block, int channels, int channel, int at) {
		if (at >= 0) {
			return block[at * channels + channel];
		}

		return history[channel][MAX_ITD_SAMPLES + at];
	}

	/** Keeps each speaker's tail so the next block's delay taps can reach back. */
	private void remember(short[] block, int channels, int frames) {
		int keep = Math.min(MAX_ITD_SAMPLES, frames);

		for (int channel = 0; channel < channels; channel++) {
			short[] tail = history[channel];

			for (int index = 0; index < MAX_ITD_SAMPLES - keep; index++) {
				tail[index] = tail[index + keep];
			}

			for (int index = 0; index < keep; index++) {
				tail[MAX_ITD_SAMPLES - keep + index] =
					block[(frames - keep + index) * channels + channel];
			}
		}
	}

	private static short clip(double sample) {
		if (sample > Short.MAX_VALUE) {
			return Short.MAX_VALUE;
		}

		if (sample < Short.MIN_VALUE) {
			return Short.MIN_VALUE;
		}

		return (short) sample;
	}

	private void queue(Voice voice, short[] samples) {
		Integer buffer = voice.free.pollFirst();

		if (buffer == null) {
			return;
		}

		AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, samples, SAMPLE_RATE);
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

	/**
	 * Rebuilds the speaker-to-ear shares from where everything stands.
	 *
	 * Each speaker gets a constant-power split across the two ears, steered by
	 * its lateral angle and softened by SEPARATION, and multiplied by its own
	 * distance fade - so standing near the left edge of a wide wall genuinely
	 * favours the left channel, exactly as it would with a real pair. Facing
	 * away flips the split with the geometry, and nothing ever reaches zero in
	 * one ear while the other still plays.
	 *
	 * The overall level (the player's sliders, the screen's volume, the
	 * focus-duck compensation) stays on the source gain: `master` is read raw
	 * from the options while the OpenAL listener also carries the master
	 * volume - that is how Minecraft applies it - so left alone it would apply
	 * twice, and the listener is also where focus-idling mods duck everything
	 * when the window goes to the background, which a television should play
	 * straight through. The listener's contribution is divided back out and
	 * capped.
	 */
	private void mixGains(double[] listener, float master) {
		double[] at = points;
		Voice out = voice;
		int speakers = stereo ? 2 : 1;

		if (out == null || listener == null || at == null || at.length < speakers * 3) {
			return;
		}

		double nearest = Double.MAX_VALUE;

		for (int index = 0; index < speakers; index++) {
			double dx = at[index * 3] - listener[0];
			double dy = at[index * 3 + 1] - listener[1];
			double dz = at[index * 3 + 2] - listener[2];
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

			nearest = Math.min(nearest, distance);

			if (direct()) {
				continue;
			}

			// the lateral component of the direction, off the camera's own left
			// vector; sign flipped so positive means the speaker is to the right
			double side = -(dx * listener[3] + dy * listener[4] + dz * listener[5]);
			double pan = distance < 1.0e-6
				? 0.0
				: Math.max(-1.0, Math.min(1.0, side / distance));

			double widened = Math.max(-1.0, Math.min(1.0, pan * WIDEN));
			double fade = falloff(distance);
			double steer = SEPARATION * widened;

			earLeft[index] = fade * Math.sqrt((1.0 - steer) / 2.0);
			earRight[index] = fade * Math.sqrt((1.0 + steer) / 2.0);

			// the far ear hears this speaker late, in proportion to how far
			// off centre it sits; the near ear is never delayed
			double lag = Math.abs(widened) * MAX_ITD_SAMPLES;

			delayLeftTarget[index] = widened > 0.0 ? lag : 0.0;
			delayRightTarget[index] = widened < 0.0 ? lag : 0.0;
		}

		if (!direct()) {
			// Both speakers land in both ears, so correlated content sums: left
			// unchecked the mix peaked at 1.3x full scale and lived inside the
			// clipper, which is "loud and smeared" in one number. The louder
			// ear's total is held to unity and the balance kept.
			double sumLeft = earLeft[0] + (speakers > 1 ? earLeft[1] : 0.0);
			double sumRight = earRight[0] + (speakers > 1 ? earRight[1] : 0.0);
			double headroom = Math.max(1.0, Math.max(sumLeft, sumRight));

			for (int index = 0; index < speakers; index++) {
				earLeft[index] /= headroom;
				earRight[index] /= headroom;
			}

			// three samples a pump: a full ITD swing lands within ~40ms, so
			// the timing cue follows a head turn about as fast as the ear
			// notices, without stepping far enough per block to click
			for (int index = 0; index < speakers; index++) {
				delayLeft[index] += Math.max(-3.0,
					Math.min(3.0, delayLeftTarget[index] - delayLeft[index]));
				delayRight[index] += Math.max(-3.0,
					Math.min(3.0, delayRightTarget[index] - delayRight[index]));
			}
		}

		// direct mode fades by the closest speaker on the source gain, which
		// applies at render time: movement and rotation cost zero latency
		double desired = master * volume / 100.0
			* (direct() ? falloff(nearest) : 1.0);
		float listenerGain = AL10.alGetListenerf(AL10.AL_GAIN);
		float gain = listenerGain > 1.0e-4f
			? (float) Math.min(desired / listenerGain, MAX_COMPENSATION_GAIN)
			: 0.0f;

		AL10.alSourcef(out.source, AL10.AL_GAIN, gain);
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

		if (voice != null) {
			voice.destroy();
		}
	}

	/** The screen's stereo output: its OpenAL source and the buffers it cycles. */
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

			// A stereo source is never spatialized, which is the whole design:
			// the mix this class bakes into each block reaches the output
			// verbatim. Relative at the origin only so no stale world position
			// can confuse an implementation that peeks anyway.
			AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
			AL10.alSource3f(source, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
			AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
			AL10.alSourcef(source, AL10.AL_PITCH, 1.0f);
			AL10.alSourcef(source, AL10.AL_GAIN, 0.0f);

			// Belt and braces against a setup that spatializes anyway: OpenAL
			// Soft's per-source spatialize switch is told no outright where the
			// extension exists (0x1214, AL_SOFT_source_spatialize), and zero
			// rolloff neutralises the distance model for any renderer that
			// insists - both no-ops on a conforming implementation.
			if (AL10.alIsExtensionPresent("AL_SOFT_source_spatialize")) {
				AL10.alSourcei(source, 0x1214, AL10.AL_FALSE);
			}

			AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0f);

			// sources clamp their gain to 1.0 by default, which would silently
			// swallow the duck compensation computed in mixGains()
			AL10.alSourcef(source, AL10.AL_MAX_GAIN, MAX_COMPENSATION_GAIN);
		}

		/** A right-sized staging array for one mixed stereo block. */
		private short[] scratch(int samples) {
			if (scratch.length != samples) {
				scratch = new short[samples];
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
