package dev.belikhun.luna.tv.browser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.belikhun.luna.tv.TvConfig;

/**
 * One headless Chromium, owned by one screen.
 *
 * A process per screen is what makes per-screen audio possible: Chromium routes
 * its output to the sink named by {@code PULSE_SINK} in its own environment, and
 * a single browser hosting several tabs could not be split apart afterwards.
 * It also keeps a wedged page out of the server: this JVM only holds a socket.
 */
public final class ChromiumProcess {

	private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Ports handed out and not yet released.
	 *
	 * A bind probe alone is not enough: two screens starting together both probe
	 * the same port, both find it free because neither has launched yet, and
	 * Chromium then binds one to 127.0.0.1 and the other to ::1 - so neither
	 * fails, and both plugin-side browsers end up driving whichever one answers
	 * on IPv4. This set closes that window.
	 */
	private static final Set<Integer> CLAIMED = new HashSet<>();

	private final Process process;
	private final int port;
	private final String websocketUrl;

	private ChromiumProcess(Process process, int port, String websocketUrl) {
		this.process = process;
		this.port = port;
		this.websocketUrl = websocketUrl;
	}

	/**
	 * Launches Chromium and waits until its DevTools endpoint answers.
	 *
	 * @param config the plugin configuration
	 * @param profileDir a private profile directory for this screen
	 * @param sinkName the PulseAudio sink this browser's audio must land in, or
	 *                 null to leave the environment alone
	 * @param width viewport width in pixels
	 * @param height viewport height in pixels
	 * @param url the first page to open
	 * @return a running process with a resolved page websocket
	 * @throws IOException if the process cannot be started or never opens the port
	 */
	public static ChromiumProcess launch(
		TvConfig config,
		Path profileDir,
		String sinkName,
		int width,
		int height,
		String url
	) throws IOException {
		int port = freePort(config.debugPortStart());
		List<String> command = new ArrayList<>();

		command.add(config.executable());
		command.addAll(config.switches());
		command.add("--remote-debugging-port=" + port);
		// pinned to IPv4 because that is what the client connects over; without it
		// Chromium may take ::1 instead and a port clash goes unnoticed
		command.add("--remote-debugging-address=127.0.0.1");
		// loopback only: the port is an internal control channel, never a service
		command.add("--remote-allow-origins=*");
		command.add("--user-data-dir=" + profileDir.toAbsolutePath());
		command.add("--window-size=" + width + "," + height);
		command.add("--force-device-scale-factor=1");
		command.add("--hide-scrollbars");
		// deliberately no --mute-audio: Chromium tests that switch by presence, so
		// even "--mute-audio=false" silences the browser
		command.add(url);

		// a force-killed browser leaves Singleton* symlinks behind; a fresh
		// chromium that trusts them hands its command line to a ghost and exits
		// without ever opening the CDP port. We own this profile's lifecycle, so
		// stale locks are always safe to clear here.
		for (String stale : new String[] { "SingletonLock", "SingletonCookie", "SingletonSocket" }) {
			try {
				java.nio.file.Files.deleteIfExists(profileDir.resolve(stale));
			} catch (IOException ignored) {
				// a lock that cannot be removed will fail the launch loudly below
			}
		}

		ProcessBuilder builder = new ProcessBuilder(command);

		builder.redirectErrorStream(true);
		// kept, not discarded: when a launch times out, this file is the only
		// place Chromium's actual complaint exists
		builder.redirectOutput(ProcessBuilder.Redirect.to(profileDir.resolve("chromium.log").toFile()));

		if (sinkName != null && !sinkName.isBlank()) {
			builder.environment().put("PULSE_SINK", sinkName);
		}

		Process process;

		try {
			process = builder.start();
		} catch (IOException exception) {
			releasePort(port);

			throw exception;
		}

		String websocketUrl = awaitPageTarget(process, port, config.startupTimeoutSeconds());

		if (websocketUrl == null) {
			process.destroyForcibly();
			releasePort(port);

			throw new IOException("Chromium không mở được cổng CDP " + port + " trong "
				+ config.startupTimeoutSeconds() + "s");
		}

		return new ChromiumProcess(process, port, websocketUrl);
	}

	/**
	 * Finds a free loopback TCP port at or above a starting point.
	 *
	 * Bind-probed rather than tracked in a table: Chromium is what actually
	 * claims the port, and anything else on the host may claim it first.
	 *
	 * @param start the first port to try
	 * @return a port that was free at the moment of checking
	 * @throws IOException if nothing in the scan window is free
	 */
	private static int freePort(int start) throws IOException {
		synchronized (CLAIMED) {
			for (int port = start; port < start + 200; port++) {
				if (CLAIMED.contains(port)) {
					continue;
				}

				// probed on the same address Chromium is told to bind, so a busy port
				// is actually detected
				try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
					int found = probe.getLocalPort();

					CLAIMED.add(found);

					return found;
				} catch (IOException ignored) {
					// taken by something else; try the next one
				}
			}
		}

		throw new IOException("Không còn cổng trống cho CDP từ " + start);
	}

	/** Gives a port back once its browser is gone. */
	private static void releasePort(int port) {
		synchronized (CLAIMED) {
			CLAIMED.remove(port);
		}
	}

	private static String awaitPageTarget(Process process, int port, int timeoutSeconds) {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(HTTP_TIMEOUT)
			.build();

		long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();

		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				return null;
			}

			String found = pageTarget(http, port);

			if (found != null) {
				return found;
			}

			try {
				Thread.sleep(POLL_INTERVAL.toMillis());
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();

				return null;
			}
		}

		return null;
	}

	private static String pageTarget(HttpClient http, int port) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + "/json/list"))
				.timeout(HTTP_TIMEOUT)
				.GET()
				.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				return null;
			}

			JsonElement parsed = JsonParser.parseString(response.body());

			if (!parsed.isJsonArray()) {
				return null;
			}

			return firstPage(parsed.getAsJsonArray());
		} catch (Exception exception) {
			return null;
		}
	}

	private static String firstPage(JsonArray targets) {
		for (JsonElement element : targets) {
			JsonObject target = element.getAsJsonObject();

			if (!target.has("type") || !target.has("webSocketDebuggerUrl")) {
				continue;
			}

			// extension background pages are targets too, and are not the tab
			if (!"page".equals(target.get("type").getAsString())) {
				continue;
			}

			return target.get("webSocketDebuggerUrl").getAsString();
		}

		return null;
	}

	/** The resolved page websocket a {@link CdpConnection} connects to. */
	public String websocketUrl() {
		return websocketUrl;
	}

	public int port() {
		return port;
	}

	public boolean alive() {
		return process.isAlive();
	}

	public long pid() {
		return process.pid();
	}

	/** Kills the browser, waiting briefly for a clean exit first. */
	public void stop() {
		process.destroy();

		try {
			if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		} finally {
			releasePort(port);
		}
	}

	/** Environment probe used by the diagnostics command. */
	public static boolean executableUsable(TvConfig config) {
		Path path = Path.of(config.executable());

		return java.nio.file.Files.isExecutable(path);
	}
}
