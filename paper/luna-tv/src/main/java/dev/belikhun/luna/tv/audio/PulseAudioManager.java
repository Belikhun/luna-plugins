package dev.belikhun.luna.tv.audio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.tv.TvConfig;

/**
 * The PulseAudio side of audio: one null sink per screen.
 *
 * A null sink is a device that goes nowhere and has a {@code .monitor} source
 * anyone can record. Giving each screen its own means each Chromium can be
 * pointed at its own sink through {@code PULSE_SINK} and recorded separately,
 * which is what makes per-screen audio possible at all: two browsers sharing a
 * sink could not be told apart afterwards.
 */
public final class PulseAudioManager {

	private static final long COMMAND_TIMEOUT_SECONDS = 10L;

	private final LunaLogger logger;

	private volatile TvConfig config;
	private volatile String unavailableReason;

	public PulseAudioManager(LunaLogger logger, TvConfig config) {
		this.logger = logger;
		this.config = config;
	}

	public void config(TvConfig config) {
		this.config = config;
	}

	/**
	 * Checks that pactl works, remembering why it does not.
	 *
	 * @return true when PulseAudio can be driven
	 */
	public boolean probe() {
		if (!config.audioEnabled()) {
			unavailableReason = "audio.enabled = false";

			return false;
		}

		Result result = run(config.pactlPath(), "info");

		if (result.exit() != 0) {
			unavailableReason = result.exit() == Result.NOT_FOUND
				? "không tìm thấy " + config.pactlPath()
				: "pactl info thất bại: " + result.firstLine();

			return false;
		}

		unavailableReason = null;

		return true;
	}

	/** Why audio is unavailable, or null when it is fine. */
	public String unavailableReason() {
		return unavailableReason;
	}

	/**
	 * The sink name for a screen.
	 *
	 * Sink names take a narrow character set, so anything outside it is folded
	 * to an underscore; a screen called "phòng khách" still needs a valid sink.
	 *
	 * @param screenName the screen's name
	 * @return the PulseAudio sink name to use
	 */
	public String sinkName(String screenName) {
		String cleaned = screenName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");

		return config.sinkPrefix() + "_" + cleaned;
	}

	/**
	 * Creates the screen's null sink if it is not already loaded.
	 *
	 * @param screenName the screen's name
	 * @return the sink name, or null when it could not be created
	 */
	public String ensureSink(String screenName) {
		String sink = sinkName(screenName);

		if (sinkExists(sink)) {
			return sink;
		}

		Result result = run(config.pactlPath(), "load-module", "module-null-sink",
			"sink_name=" + sink,
			"sink_properties=device.description=LunaTV-" + sink);

		if (result.exit() != 0) {
			logger.warn("Không tạo được null-sink '" + sink + "': " + result.firstLine());

			return null;
		}

		return sink;
	}

	/**
	 * Unloads a screen's null sink.
	 *
	 * @param screenName the screen's name
	 */
	public void removeSink(String screenName) {
		String sink = sinkName(screenName);
		String moduleId = sinkModuleId(sink);

		if (moduleId == null) {
			return;
		}

		run(config.pactlPath(), "unload-module", moduleId);
	}

	private boolean sinkExists(String sink) {
		Result result = run(config.pactlPath(), "list", "short", "sinks");

		if (result.exit() != 0) {
			return false;
		}

		for (String line : result.lines()) {
			// columns are tab separated: index, name, driver, format, state
			String[] columns = line.split("\t");

			if (columns.length > 1 && columns[1].equals(sink)) {
				return true;
			}
		}

		return false;
	}

	private String sinkModuleId(String sink) {
		Result result = run(config.pactlPath(), "list", "short", "modules");

		if (result.exit() != 0) {
			return null;
		}

		for (String line : result.lines()) {
			if (!line.contains("module-null-sink") || !line.contains("sink_name=" + sink)) {
				continue;
			}

			String[] columns = line.split("\t");

			if (columns.length > 0) {
				return columns[0];
			}
		}

		return null;
	}

	private Result run(String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);

			builder.redirectErrorStream(true);

			Process process = builder.start();
			List<String> lines = new ArrayList<>();

			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

				String line;

				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}

			if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();

				return new Result(Result.TIMEOUT, List.of("hết thời gian chờ"));
			}

			return new Result(process.exitValue(), lines);
		} catch (java.io.IOException exception) {
			return new Result(Result.NOT_FOUND, List.of(String.valueOf(exception.getMessage())));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();

			return new Result(Result.TIMEOUT, List.of("bị ngắt"));
		}
	}

	private record Result(int exit, List<String> lines) {

		static final int NOT_FOUND = -1;
		static final int TIMEOUT = -2;

		String firstLine() {
			return lines.isEmpty() ? "" : lines.get(0);
		}
	}
}
