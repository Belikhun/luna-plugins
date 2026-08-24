package dev.belikhun.luna.tv.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * One screen's picture, re-encoded from Chromium's JPEG into H.264.
 *
 * The reason is bandwidth, and the numbers are not close. A 1664x896 JPEG at
 * quality 85 is about a megabyte, so thirty of them a second is 240 Mbit/s per
 * viewer; every frame is a whole picture because MJPEG has no notion of the one
 * before it. H.264 at the same size and frame rate is a few megabits, because
 * almost every frame is a description of what changed. On any link that is not
 * a LAN that is the difference between a wall that plays and a wall that stops.
 *
 * ffmpeg runs as a child process rather than a library. There is no JNI to get
 * wrong, an encoder that dies takes a child with it and not the server, and the
 * shape matches what this plugin already does for Chromium and parec.
 *
 * The child is started when the first viewer asks for H.264 and killed when the
 * last one leaves, so a screen nobody is watching over the stream costs nothing
 * beyond the browser it was already running.
 */
public final class H264Encoder {

	/** Bytes of Annex-B read at a time; a frame is usually several of these. */
	private static final int READ_CHUNK = 64 * 1024;

	/**
	 * Seconds between keyframes.
	 *
	 * Every keyframe is the one frame a newcomer can start decoding at, and it
	 * costs many times what a normal frame does. One second is the compromise:
	 * a viewer walking up to a screen waits at most that long for a picture,
	 * and the bitrate spends a bearable share on it.
	 */
	private static final int KEYFRAME_SECONDS = 1;

	private final LunaLogger logger;
	private final String screenName;
	private final String executable;
	private final int width;
	private final int height;
	private final int fps;
	private final int kilobits;

	/** The render node to encode on, or null for software; cleared on failure. */
	private volatile String vaapiDevice;

	private final AtomicLong framesIn = new AtomicLong();
	private final AtomicLong bytesOut = new AtomicLong();

	/** Receives each Annex-B chunk as it leaves the encoder. */
	private final java.util.function.Consumer<byte[]> sink;

	private volatile Process process;
	private volatile OutputStream input;
	private volatile Thread reader;
	private volatile Thread errors;
	private volatile boolean running;
	private volatile String failure;

	/** When a frame last went in, for the idle heartbeat. */
	private volatile long lastOfferMs;

	public H264Encoder(
		LunaLogger logger,
		String screenName,
		String executable,
		String vaapiDevice,
		int width,
		int height,
		int fps,
		int kilobits,
		java.util.function.Consumer<byte[]> sink
	) {
		this.vaapiDevice = vaapiDevice;
		this.logger = logger;
		this.screenName = screenName;
		this.executable = executable;
		this.width = width;
		this.height = height;
		this.fps = Math.max(1, fps);
		this.kilobits = Math.max(200, kilobits);
		this.sink = sink;
	}

	/**
	 * Starts the encoder.
	 *
	 * @return true when the child is running
	 */
	public boolean start() {
		if (running) {
			return true;
		}

		try {
			ProcessBuilder builder = new ProcessBuilder(command());

			builder.redirectErrorStream(false);

			Process started = builder.start();

			process = started;
			input = started.getOutputStream();
			running = true;

			reader = thread("LunaTv-H264-" + screenName, () -> drain(started.getInputStream()));
			errors = thread("LunaTv-H264-err-" + screenName, () -> complain(started.getErrorStream()));

			logger.info("Bộ mã hoá H.264 cho '" + screenName + "' đã chạy (pid "
				+ started.pid() + ", " + width + "x" + height + " @" + fps + ", "
				+ kilobits + " kbit/s, " + (vaapiDevice != null ? "GPU" : "CPU") + ").");

			return true;
		} catch (IOException exception) {
			failure = exception.getMessage();
			logger.warn("Không chạy được ffmpeg cho '" + screenName + "': " + exception.getMessage());

			return false;
		}
	}

	/**
	 * The command line, which is entirely about latency.
	 *
	 * ultrafast and zerolatency together turn off everything that works by
	 * looking ahead, which is what an encoder normally spends its quality on
	 * and what a live screen cannot afford to wait for. No B-frames for the
	 * same reason: a B-frame is one that needs a later frame to decode, so a
	 * single one puts a frame of delay into every picture on the wall.
	 *
	 * repeat-headers is what makes joining mid-stream possible at all: without
	 * it the parameter sets appear once at the very start and a viewer arriving
	 * afterwards has no way to interpret anything that follows.
	 */
	private List<String> command() {
		List<String> command = new ArrayList<>();
		int keyint = fps * KEYFRAME_SECONDS;
		String gpu = vaapiDevice;

		command.add(executable);
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");

		// The whole pipeline on the iGPU when there is one: the JPEG is decoded
		// by the fixed-function block, converted on the GPU, and encoded by the
		// media engine, so no frame ever exists as CPU pixels. Measured against
		// the software chain on this class of hardware it is about seven times
		// cheaper. Everything after -i is chosen per branch below.
		if (gpu != null) {
			command.add("-init_hw_device");
			command.add("vaapi=gpu:" + gpu);
			command.add("-hwaccel");
			command.add("vaapi");
			command.add("-hwaccel_output_format");
			command.add("vaapi");
			command.add("-hwaccel_device");
			command.add("gpu");
		}

		// low_delay and a tiny probe keep ffmpeg from holding frames back while
		// it works out what the input is. `-fflags nobuffer` belongs in that list
		// by reputation and is deliberately absent: it discards everything read
		// during the probe, which measured against a 120-frame clip left two
		// frames, and it buys nothing the other two do not already give.
		command.add("-flags");
		command.add("low_delay");
		command.add("-probesize");
		command.add("32");
		command.add("-analyzeduration");
		command.add("0");

		// the input is the exact byte stream Chromium hands us, one JPEG after
		// another, with the frame rate asserted rather than guessed from timing
		command.add("-f");
		command.add("mjpeg");
		command.add("-framerate");
		command.add(Integer.toString(fps));
		command.add("-i");
		command.add("pipe:0");

		command.add("-an");

		if (gpu != null) {
			// the format conversion has to be spelled out: the JPEG block hands
			// back a full-range surface the encoder refuses as it is, and the
			// VPP pass is what turns it into the NV12 the encoder wants
			command.add("-vf");
			command.add("scale_vaapi=format=nv12");
			command.add("-c:v");
			command.add("h264_vaapi");
			command.add("-bf");
			command.add("0");
			command.add("-g");
			command.add(Integer.toString(keyint));
		} else {
			command.add("-c:v");
			command.add("libx264");
			command.add("-preset");
			command.add("ultrafast");
			command.add("-tune");
			command.add("zerolatency");
			command.add("-pix_fmt");
			command.add("yuv420p");
			command.add("-bf");
			command.add("0");
			command.add("-g");
			command.add(Integer.toString(keyint));
			command.add("-x264-params");
			command.add("keyint=" + keyint + ":min-keyint=" + keyint
				+ ":scenecut=0:repeat-headers=1:sliced-threads=1");
		}

		// A ceiling rather than a target, and the buffer is the latency. The
		// stream may only burst ahead of the bitrate by one bufsize, so bufsize
		// in seconds is exactly how far the picture is allowed to fall behind
		// before rate control reins it in: half a second of buffer was half a
		// second of allowed lag, spent almost entirely on keyframes, and every
		// keyframe burst read as the stream stumbling. A sixth of a second keeps
		// keyframes three times smaller at the cost of them being plainer, which
		// on a screen that repaints within a second is invisible.
		command.add("-b:v");
		command.add(kilobits + "k");
		command.add("-maxrate");
		command.add(kilobits + "k");
		command.add("-bufsize");
		command.add(Math.max(300, kilobits / 6) + "k");

		command.add("-f");
		command.add("h264");
		command.add("pipe:1");

		return command;
	}

	/**
	 * Hands one JPEG frame to the encoder.
	 *
	 * Called on the decode thread, and once a second by the idle heartbeat,
	 * which is why it is synchronized: two writers interleaving bytes into one
	 * pipe would hand the encoder half of each of two JPEGs. A write that
	 * blocks means the encoder is behind, which on a screen is better answered
	 * by waiting one frame than by dropping one: unlike MJPEG, every frame here
	 * is something the next frames are described against.
	 *
	 * @param jpeg the frame exactly as Chromium encoded it
	 */
	public synchronized void offer(byte[] jpeg) {
		OutputStream out = input;

		if (!running || out == null) {
			return;
		}

		// A hardware chain can be broken in a way that never throws: the child
		// stays up, reports an error per frame on stderr, and produces nothing.
		// Three seconds of frames in with zero bytes out is the verdict, and the
		// sentence is the software encoder, which is slower and works.
		if (vaapiDevice != null && bytesOut.get() == 0 && framesIn.get() >= fps * 3L) {
			fallback("nhận " + framesIn.get() + " khung mà không ra dữ liệu");
			out = input;

			if (out == null) {
				return;
			}
		}

		try {
			out.write(jpeg);
			out.flush();
			framesIn.incrementAndGet();
			lastOfferMs = System.currentTimeMillis();
		} catch (IOException broken) {
			// a GPU child that died before its first byte gets one retry on the
			// CPU; anything else broken here is genuinely the end of the stream
			if (running && vaapiDevice != null && bytesOut.get() == 0) {
				fallback("ffmpeg đóng sớm: " + broken.getMessage());

				return;
			}

			if (running) {
				failure = "ffmpeg đã đóng: " + broken.getMessage();
				logger.warn("Bộ mã hoá H.264 của '" + screenName + "' đứt: " + broken.getMessage());
			}

			stop();
		}
	}

	/**
	 * Abandons the GPU and starts over on the CPU, once.
	 *
	 * One-way by construction: the device is forgotten before the restart, so
	 * the rebuilt command takes the software branch and this method can never
	 * run a second time. Called only from the feeding thread, which is the one
	 * place the child's stdin is touched, so nothing else can be mid-write.
	 */
	private void fallback(String reason) {
		if (!running || vaapiDevice == null) {
			return;
		}

		logger.warn("Mã hoá GPU cho '" + screenName + "' hỏng (" + reason
			+ "), chuyển sang CPU.");
		vaapiDevice = null;

		OutputStream oldInput = input;

		input = null;

		if (oldInput != null) {
			try {
				oldInput.close();
			} catch (IOException ignored) {
				// the pipe may already be gone, which is why we are here
			}
		}

		Process old = process;

		process = null;

		if (old != null) {
			old.destroyForcibly();
		}

		framesIn.set(0);
		running = false;
		start();
	}

	private void drain(InputStream from) {
		byte[] buffer = new byte[READ_CHUNK];

		try {
			while (running) {
				int read = from.read(buffer);

				if (read < 0) {
					break;
				}

				if (read == 0) {
					continue;
				}

				byte[] chunk = new byte[read];

				System.arraycopy(buffer, 0, chunk, 0, read);
				bytesOut.addAndGet(read);
				sink.accept(chunk);
			}
		} catch (IOException ended) {
			// the child was killed, which is the normal way this stops
		}
	}

	private void complain(InputStream from) {
		try (java.io.BufferedReader lines = new java.io.BufferedReader(
				new java.io.InputStreamReader(from, java.nio.charset.StandardCharsets.UTF_8))) {
			String line;

			while ((line = lines.readLine()) != null) {
				failure = line;
				logger.warn("ffmpeg (" + screenName + "): " + line);
			}
		} catch (IOException ended) {
			// same
		}
	}

	private Thread thread(String name, Runnable body) {
		Thread started = new Thread(body, name);

		started.setDaemon(true);
		started.start();

		return started;
	}

	/** Kills the child and forgets it. */
	public void stop() {
		if (!running) {
			return;
		}

		running = false;

		OutputStream out = input;

		input = null;

		if (out != null) {
			try {
				out.close();
			} catch (IOException ignored) {
				// closing a pipe whose far end is already gone
			}
		}

		Process current = process;

		process = null;

		if (current != null) {
			current.destroyForcibly();
		}

		Thread reading = reader;

		if (reading != null) {
			reading.interrupt();
		}

		Thread complaining = errors;

		if (complaining != null) {
			complaining.interrupt();
		}

		reader = null;
		errors = null;
	}

	public boolean alive() {
		Process current = process;

		return running && current != null && current.isAlive();
	}

	/** Frames fed in since the encoder started. */
	public long framesIn() {
		return framesIn.get();
	}

	/** When a frame last went in, as epoch millis; 0 before the first. */
	public long lastOfferMs() {
		return lastOfferMs;
	}

	/** Compressed bytes produced since the encoder started. */
	public long bytesOut() {
		return bytesOut.get();
	}

	/** The last thing ffmpeg complained about, or null. */
	public String failure() {
		return failure;
	}
}
