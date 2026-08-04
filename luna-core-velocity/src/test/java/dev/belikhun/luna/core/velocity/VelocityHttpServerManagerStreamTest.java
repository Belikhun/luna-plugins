package dev.belikhun.luna.core.velocity;

import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.SseBroadcaster;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the streaming HTTP path: a real server on a real port, a real
 * client reading a real event stream. The SSE plumbing depends on the exchange
 * staying open after the handler returns, which no unit test of the pieces can show.
 */
final class VelocityHttpServerManagerStreamTest {
	private static final String SECRET = "test-forwarding-secret";

	private VelocityHttpServerManager manager;
	private SseBroadcaster broadcaster;

	@AfterEach
	void tearDown() {
		if (broadcaster != null) {
			broadcaster.close();
		}
		if (manager != null) {
			manager.stop();
		}
	}

	@Test
	void streamsEventsToASubscriberAndGatesOnTheToken(@TempDir Path dataDirectory) throws Exception {
		int port = freePort();
		LunaLogger logger = LunaLogger.forLogger(Logger.getLogger("test"), false);
		RequestAuthorizer authorizer = new RequestAuthorizer(SECRET);

		manager = new VelocityHttpServerManager(logger);
		broadcaster = new SseBroadcaster(logger, "test");

		manager.router().get("/telemetry", request -> {
			if (!authorizer.authorized(request)) {
				return authorizer.unauthorized();
			}

			return broadcaster.subscribe(stream -> stream.event("snapshot", LunaJson.write(Map.of("backends", 3))));
		});

		manager.router().get("/plain", request -> HttpResponse.json(200, "{\"ok\":true}"));
		manager.startIfEnabled(writeConfig(dataDirectory, port));

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		String base = "http://127.0.0.1:" + port + "/api";

		// an unauthenticated stream request is refused outright
		var refused = client.send(
			HttpRequest.newBuilder(URI.create(base + "/telemetry")).GET().build(),
			java.net.http.HttpResponse.BodyHandlers.ofString()
		);
		assertEquals(401, refused.statusCode());

		List<String> received = new ArrayList<>();
		CountDownLatch snapshot = new CountDownLatch(1);
		CountDownLatch pushed = new CountDownLatch(1);

		Thread reader = new Thread(() -> {
			try {
				var response = client.send(
					HttpRequest.newBuilder(URI.create(base + "/telemetry"))
						.header(RequestAuthorizer.TOKEN_HEADER, SECRET)
						.GET()
						.build(),
					java.net.http.HttpResponse.BodyHandlers.ofInputStream()
				);

				assertEquals(200, response.statusCode());
				assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

				try (BufferedReader lines = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8)
				)) {
					String line;
					while ((line = lines.readLine()) != null) {
						if (line.isBlank()) {
							continue;
						}

						synchronized (received) {
							received.add(line);
						}

						if (line.contains("\"backends\":3")) {
							snapshot.countDown();
						}
						if (line.contains("\"tps\":19.5")) {
							pushed.countDown();
							return;
						}
					}
				}
			} catch (IOException | InterruptedException failure) {
				// the assertions below report the failure through the latches
			}
		});

		reader.setDaemon(true);
		reader.start();

		assertTrue(snapshot.await(10, TimeUnit.SECONDS), "no snapshot event arrived");

		// the handler has returned by now; a push must still reach the open exchange
		for (int attempt = 0; attempt < 50 && broadcaster.size() == 0; attempt++) {
			Thread.sleep(100);
		}
		assertEquals(1, broadcaster.size(), "subscriber was not registered");

		broadcaster.broadcast("backend", LunaJson.write(Map.of("tps", 19.5D)));
		assertTrue(pushed.await(10, TimeUnit.SECONDS), "broadcast never reached the subscriber");

		synchronized (received) {
			assertTrue(received.contains("event: snapshot"), received.toString());
			assertTrue(received.contains("event: backend"), received.toString());
			assertTrue(received.stream().anyMatch(line -> line.startsWith("retry:")), received.toString());
		}

		// a plain response on the same server is unaffected by the streaming route
		var plain = client.send(
			HttpRequest.newBuilder(URI.create(base + "/plain")).GET().build(),
			java.net.http.HttpResponse.BodyHandlers.ofString()
		);
		assertEquals(200, plain.statusCode());
		assertEquals("{\"ok\":true}", plain.body());
	}

	@Test
	void dropsSubscribersWhoseConnectionDied(@TempDir Path dataDirectory) throws Exception {
		int port = freePort();
		LunaLogger logger = LunaLogger.forLogger(Logger.getLogger("test"), false);

		manager = new VelocityHttpServerManager(logger);
		broadcaster = new SseBroadcaster(logger, "test");
		manager.router().get("/telemetry", request -> broadcaster.subscribe(stream -> stream.comment("hello")));
		manager.startIfEnabled(writeConfig(dataDirectory, port));

		HttpClient client = HttpClient.newHttpClient();
		var response = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/telemetry")).GET().build(),
			java.net.http.HttpResponse.BodyHandlers.ofInputStream()
		);

		assertEquals(200, response.statusCode());
		for (int attempt = 0; attempt < 50 && broadcaster.size() == 0; attempt++) {
			Thread.sleep(100);
		}
		assertEquals(1, broadcaster.size());

		response.body().close();

		// The first write to a closed socket may still succeed into the kernel buffer,
		// so the drop is observed on a later one rather than immediately.
		for (int attempt = 0; attempt < 50 && broadcaster.size() > 0; attempt++) {
			broadcaster.broadcast("backend", "{}");
			Thread.sleep(100);
		}

		assertEquals(0, broadcaster.size(), "dead subscriber was never dropped");
	}

	private Path writeConfig(Path dataDirectory, int port) throws IOException {
		Path config = dataDirectory.resolve("config.yml");

		Files.writeString(config, String.join("\n",
			"http:",
			"  enabled: true",
			"  host: 127.0.0.1",
			"  port: " + port,
			"  pathPrefix: \"/api\"",
			""
		), StandardCharsets.UTF_8);

		return config;
	}

	private int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
