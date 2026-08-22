package dev.belikhun.luna.tv.audio;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * An Opus encoder tuned for music instead of speech.
 *
 * Simple Voice Chat's own `createEncoder` builds a Concentus encoder and never
 * sets a bitrate, so it runs at the library's automatic default (about 51 kbps
 * mono) with in-band FEC and a 5% expected packet loss. Those are the right
 * choices for a microphone on a bad connection and the wrong ones for a page
 * playing music on a LAN: the redundancy eats a third of an already low budget.
 *
 * The API exposes no way to configure any of that, but it does accept any
 * {@link OpusEncoder}, and Concentus ships inside the voicechat jar we already
 * join the classpath of. So the encoder is built directly and set to a real
 * music bitrate. Reflection rather than a compile-time reference: the classes
 * live in the plugin, not in voicechat-api, and a build of voicechat that
 * moved or shaded them must degrade to the stock encoder rather than fail to
 * load this class.
 */
public final class HighQualityEncoder implements OpusEncoder {

	private static final String CONCENTUS = "de.maxhenkel.voicechat.concentus.";

	/** Opus's own ceiling; a request above this is clamped by the library. */
	public static final int MAX_BITRATE = 512_000;

	/**
	 * Highest complexity, i.e. the encoder spends the most CPU per frame for
	 * the best quality at a given bitrate.
	 *
	 * Affordable here because one screen is 50 frames a second of mono audio,
	 * next to nothing beside the video path, and because it is what makes a
	 * pure-Java encoder competitive with the native one.
	 */
	private static final int COMPLEXITY = 10;

	private final Object encoder;
	private final Method encode;
	private final byte[] buffer = new byte[4096];

	private volatile boolean closed;

	private HighQualityEncoder(Object encoder, Method encode) {
		this.encoder = encoder;
		this.encode = encode;
	}

	/**
	 * Builds a music-tuned encoder, or returns null when Concentus is not where
	 * it is expected to be.
	 *
	 * @param logger scoped logger, for the one line explaining a fallback
	 * @param bitrate bits per second, e.g. 128000
	 * @return the encoder, or null to fall back to voice chat's own
	 */
	public static HighQualityEncoder create(LunaLogger logger, int bitrate) {
		try {
			ClassLoader loader = OpusEncoder.class.getClassLoader();
			Class<?> encoderType = Class.forName(CONCENTUS + "OpusEncoder", true, loader);
			Class<?> applicationType = Class.forName(CONCENTUS + "OpusApplication", true, loader);
			Class<?> bandwidthType = Class.forName(CONCENTUS + "OpusBandwidth", true, loader);
			Class<?> signalType = Class.forName(CONCENTUS + "OpusSignal", true, loader);

			Constructor<?> constructor = encoderType.getConstructor(int.class, int.class, applicationType);
			Object application = enumOf(applicationType, "OPUS_APPLICATION_AUDIO");
			Object built = constructor.newInstance(48_000, 1, application);

			// the whole point: a real music bitrate instead of Opus's automatic
			// one, which lands near 51 kbps for mono
			encoderType.getMethod("setBitrate", int.class).invoke(built, bitrate);
			encoderType.getMethod("setComplexity", int.class).invoke(built, COMPLEXITY);
			encoderType.getMethod("setUseVBR", boolean.class).invoke(built, true);
			encoderType.getMethod("setUseConstrainedVBR", boolean.class).invoke(built, false);

			// FEC and a packet-loss estimate spend bitrate on redundancy for a
			// lossy link. This is a server talking to players on its own network,
			// and a lost 20ms of music is not worth a third of the budget
			encoderType.getMethod("setUseInbandFEC", boolean.class).invoke(built, false);
			encoderType.getMethod("setPacketLossPercent", int.class).invoke(built, 0);

			// never let the encoder narrow the band to save bits: at these rates
			// there is no reason to drop the top octave
			encoderType.getMethod("setMaxBandwidth", bandwidthType)
				.invoke(built, enumOf(bandwidthType, "OPUS_BANDWIDTH_FULLBAND"));
			encoderType.getMethod("setBandwidth", bandwidthType)
				.invoke(built, enumOf(bandwidthType, "OPUS_BANDWIDTH_FULLBAND"));
			encoderType.getMethod("setSignalType", signalType)
				.invoke(built, enumOf(signalType, "OPUS_SIGNAL_MUSIC"));

			// DTX stops sending during silence, which reads as a dropped stream
			// to a listener waiting on a video's quiet passage
			encoderType.getMethod("setUseDTX", boolean.class).invoke(built, false);

			Method encode = encoderType.getMethod("encode",
				short[].class, int.class, int.class, byte[].class, int.class, int.class);

			return new HighQualityEncoder(built, encode);
		} catch (Throwable throwable) {
			logger.warn("Không dựng được bộ mã hoá Opus riêng (" + throwable
				+ "), dùng bộ mặc định của voice chat.");

			return null;
		}
	}

	private static Object enumOf(Class<?> type, String name) throws Exception {
		return type.getField(name).get(null);
	}

	@Override
	public byte[] encode(short[] samples) {
		if (closed) {
			return new byte[0];
		}

		try {
			int written = (int) encode.invoke(encoder,
				samples, 0, ParecCapture.FRAME_SAMPLES, buffer, 0, buffer.length);

			if (written <= 0) {
				return new byte[0];
			}

			byte[] packet = new byte[written];

			System.arraycopy(buffer, 0, packet, 0, written);

			return packet;
		} catch (Throwable throwable) {
			return new byte[0];
		}
	}

	@Override
	public void resetState() {
		if (closed) {
			return;
		}

		try {
			encoder.getClass().getMethod("resetState").invoke(encoder);
		} catch (Throwable throwable) {
			// a reset that cannot happen is not worth ending the stream over
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		closed = true;
	}
}
