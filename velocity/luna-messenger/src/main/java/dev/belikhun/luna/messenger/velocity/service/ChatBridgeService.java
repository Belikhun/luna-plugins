package dev.belikhun.luna.messenger.velocity.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.http.SseBroadcaster;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The chat bridge: lets an outside program (the Discord AI bot) take part in
 * network chat.
 *
 * <ul>
 *   <li>{@code GET /messenger/bridge/stream} is an event stream: a {@code hello}
 *   frame (who is online, which Discord channels the messenger bridges, so the
 *   program can stay out of them), then one {@code chat} frame per network chat
 *   line and per inbound Discord message. Server and direct channels are not
 *   streamed: the bridge only takes part in the network channel.</li>
 *   <li>{@code POST /messenger/bridge/chat} says something in network chat as
 *   the bridge's sender, rendered with the player chat format.</li>
 *   <li>{@code POST /messenger/bridge/bossbar} shows, updates or hides a boss bar,
 *   which is how the program shows that it is working: a chat line cannot be
 *   edited once it is sent, a bar can.</li>
 * </ul>
 *
 * Every route answers 404 while the bridge is disabled and 401 without the
 * bridge's own token. Bars expire on their own when the program stops updating
 * them, and all of them are taken down when no stream is connected, so a
 * crashed program never leaves a bar on somebody's screen.
 */
public final class ChatBridgeService {
	public static final int PROTOCOL = 1;
	private static final int MAX_BARS = 64;
	private static final int MAX_TTL_SECONDS = 600;
	private static final int MAX_MESSAGE_LENGTH = 4000;
	private static final MiniMessage MM = MiniMessage.miniMessage();

	private final ProxyServer proxyServer;
	private final LunaLogger logger;
	private final VelocityMessengerRouter router;
	private final Supplier<VelocityMessengerConfig> config;
	private final SseBroadcaster broadcaster;
	private final ExecutorService writer;
	private final ScheduledExecutorService sweeper;
	private final Map<String, BridgeBar> bars;

	public ChatBridgeService(ProxyServer proxyServer, LunaLogger logger, VelocityMessengerRouter router, Supplier<VelocityMessengerConfig> config) {
		this.proxyServer = proxyServer;
		this.logger = logger.scope("ChatBridge");
		this.router = router;
		this.config = config;
		this.broadcaster = new SseBroadcaster(logger, "chat-bridge");
		this.bars = new ConcurrentHashMap<>();

		// Chat is routed on the plugin-messaging threads; a slow stream reader must
		// never hold one up, so events are handed to a single writer.
		this.writer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "luna-chat-bridge-writer");
			thread.setDaemon(true);
			return thread;
		});

		this.sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-chat-bridge-bars");
			thread.setDaemon(true);
			return thread;
		});
		this.sweeper.scheduleAtFixedRate(this::sweepBars, 5L, 5L, TimeUnit.SECONDS);

		router.observeChat(event -> {
			if (!bridge().enabled() || broadcaster.size() == 0) {
				return;
			}

			String payload = LunaJson.write(event);
			writer.execute(() -> broadcaster.broadcast("chat", payload));
		});
	}

	public void register(Router http) {
		http.get("/messenger/bridge/stream", this::stream);
		http.post("/messenger/bridge/chat", this::chat);
		http.post("/messenger/bridge/bossbar", this::bossbar);
	}

	public void close() {
		router.observeChat(null);
		hideAllBars();
		sweeper.shutdownNow();
		writer.shutdownNow();
		broadcaster.close();
	}

	private VelocityMessengerConfig.ChatBridgeConfig bridge() {
		return config.get().chatBridge();
	}

	private HttpResponse refusal(HttpRequest request) {
		VelocityMessengerConfig.ChatBridgeConfig bridge = bridge();
		if (!bridge.enabled()) {
			return LunaJson.error(404, "chat bridge is disabled");
		}

		RequestAuthorizer authorizer = new RequestAuthorizer(bridge.token());
		if (!authorizer.authorized(request)) {
			return authorizer.unauthorized();
		}

		return null;
	}

	private HttpResponse stream(HttpRequest request) {
		HttpResponse refused = refusal(request);
		if (refused != null) {
			return refused;
		}

		logger.audit("Chat bridge đã kết nối stream.");
		return broadcaster.subscribe(stream -> stream.event("hello", LunaJson.write(hello())));
	}

	private Map<String, Object> hello() {
		VelocityMessengerConfig current = config.get();
		VelocityMessengerConfig.DiscordConfig discord = current.discord();
		List<String> discordChannels = discord.enabled() && discord.bot().enabled()
			? discord.bot().channelIds()
			: List.of();

		List<Map<String, Object>> players = new ArrayList<>();
		for (Player player : proxyServer.getAllPlayers()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("uuid", player.getUniqueId().toString());
			row.put("name", player.getUsername());
			row.put("server", player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(""));
			players.add(row);
		}

		List<String> servers = new ArrayList<>();
		for (RegisteredServer server : proxyServer.getAllServers()) {
			servers.add(server.getServerInfo().getName());
		}

		Map<String, Object> hello = new LinkedHashMap<>();
		hello.put("protocol", PROTOCOL);
		hello.put("sender", Map.of("name", current.chatBridge().senderName()));
		hello.put("discordChannels", discordChannels);
		hello.put("servers", servers);
		hello.put("players", players);
		return hello;
	}

	private HttpResponse chat(HttpRequest request) {
		long started = System.nanoTime();
		HttpResponse refused = refusal(request);
		if (refused != null) {
			return refused;
		}

		JsonObject body = parse(request);
		if (body == null) {
			return LunaJson.error(400, "the body must be a JSON object");
		}

		String message = string(body, "message");
		if (message == null || message.isBlank()) {
			return LunaJson.error(400, "message is required");
		}

		if (message.length() > MAX_MESSAGE_LENGTH) {
			return LunaJson.error(413, "message is longer than " + MAX_MESSAGE_LENGTH + " characters");
		}

		int reached = router.routeBridgeChat(string(body, "name"), message);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reached", reached);
		return LunaJson.envelope(200, payload, started);
	}

	private HttpResponse bossbar(HttpRequest request) {
		long started = System.nanoTime();
		HttpResponse refused = refusal(request);
		if (refused != null) {
			return refused;
		}

		JsonObject body = parse(request);
		if (body == null) {
			return LunaJson.error(400, "the body must be a JSON object");
		}

		String id = string(body, "id");
		if (id == null || id.isBlank() || id.length() > 128) {
			return LunaJson.error(400, "id is required (at most 128 characters)");
		}

		String action = Optional.ofNullable(string(body, "action")).orElse("show").toLowerCase(Locale.ROOT);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", id);

		if (action.equals("hide")) {
			payload.put("hidden", hideBar(id));
			return LunaJson.envelope(200, payload, started);
		}

		if (!action.equals("show")) {
			return LunaJson.error(400, "action is show or hide");
		}

		if (!bars.containsKey(id) && bars.size() >= MAX_BARS) {
			return LunaJson.error(429, "too many bars are showing");
		}

		Set<Player> targets = targets(body);
		Component title = MM.deserialize(Optional.ofNullable(string(body, "title")).orElse(""));
		float progress = (float) Math.max(0.0, Math.min(1.0, number(body, "progress", 0.0)));
		BossBar.Color color = color(string(body, "color"));
		BossBar.Overlay overlay = overlay(string(body, "overlay"));
		int ttl = (int) Math.max(5, Math.min(MAX_TTL_SECONDS, number(body, "ttlSeconds", bridge().bossbarTtlSeconds())));

		BridgeBar entry = bars.computeIfAbsent(id, ignored -> new BridgeBar(BossBar.bossBar(title, progress, color, overlay)));
		synchronized (entry) {
			entry.bar.name(title);
			entry.bar.progress(progress);
			entry.bar.color(color);
			entry.bar.overlay(overlay);
			entry.expiresAt = System.currentTimeMillis() + ttl * 1000L;

			Set<UUID> next = new HashSet<>();
			for (Player player : targets) {
				next.add(player.getUniqueId());
				player.showBossBar(entry.bar);
			}

			for (UUID viewer : entry.viewers) {
				if (!next.contains(viewer)) {
					proxyServer.getPlayer(viewer).ifPresent(player -> player.hideBossBar(entry.bar));
				}
			}

			entry.viewers.clear();
			entry.viewers.addAll(next);
		}

		payload.put("viewers", targets.size());
		return LunaJson.envelope(200, payload, started);
	}

	/** The named players, else everyone on the named server, else everyone. */
	private Set<Player> targets(JsonObject body) {
		Set<Player> targets = new HashSet<>();
		JsonElement players = body.get("players");
		if (players != null && players.isJsonArray()) {
			for (JsonElement element : (JsonArray) players) {
				if (!element.isJsonPrimitive()) {
					continue;
				}

				findPlayer(element.getAsString()).ifPresent(targets::add);
			}

			return targets;
		}

		String server = string(body, "server");
		for (Player player : proxyServer.getAllPlayers()) {
			if (server == null || server.isBlank()) {
				targets.add(player);
				continue;
			}

			String current = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
			if (current.equalsIgnoreCase(server)) {
				targets.add(player);
			}
		}

		return targets;
	}

	private Optional<Player> findPlayer(String reference) {
		try {
			return proxyServer.getPlayer(UUID.fromString(reference));
		} catch (IllegalArgumentException notUuid) {
			return proxyServer.getPlayer(reference);
		}
	}

	private boolean hideBar(String id) {
		BridgeBar entry = bars.remove(id);
		if (entry == null) {
			return false;
		}

		synchronized (entry) {
			for (UUID viewer : entry.viewers) {
				proxyServer.getPlayer(viewer).ifPresent(player -> player.hideBossBar(entry.bar));
			}

			entry.viewers.clear();
		}

		return true;
	}

	private void hideAllBars() {
		for (String id : List.copyOf(bars.keySet())) {
			hideBar(id);
		}
	}

	private void sweepBars() {
		try {
			if (bars.isEmpty()) {
				return;
			}

			if (broadcaster.size() == 0 || !bridge().enabled()) {
				hideAllBars();
				return;
			}

			long now = System.currentTimeMillis();
			for (Map.Entry<String, BridgeBar> entry : bars.entrySet()) {
				if (entry.getValue().expiresAt <= now) {
					hideBar(entry.getKey());
				}
			}
		} catch (RuntimeException exception) {
			logger.warn("Không thể dọn boss bar của chat bridge: " + exception.getMessage());
		}
	}

	private static JsonObject parse(HttpRequest request) {
		try {
			JsonElement element = JsonParser.parseString(request.bodyAsString());
			return element.isJsonObject() ? element.getAsJsonObject() : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static String string(JsonObject body, String key) {
		JsonElement element = body.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}

		return element.getAsString();
	}

	private static double number(JsonObject body, String key, double fallback) {
		JsonElement element = body.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}

		return element.getAsDouble();
	}

	private static BossBar.Color color(String name) {
		if (name != null) {
			for (BossBar.Color color : BossBar.Color.values()) {
				if (color.name().equalsIgnoreCase(name)) {
					return color;
				}
			}
		}

		return BossBar.Color.BLUE;
	}

	private static BossBar.Overlay overlay(String name) {
		if (name != null) {
			for (BossBar.Overlay overlay : BossBar.Overlay.values()) {
				if (overlay.name().equalsIgnoreCase(name)) {
					return overlay;
				}
			}
		}

		return BossBar.Overlay.PROGRESS;
	}

	private static final class BridgeBar {
		private final BossBar bar;
		private final Set<UUID> viewers;
		private volatile long expiresAt;

		private BridgeBar(BossBar bar) {
			this.bar = bar;
			this.viewers = new HashSet<>();
		}
	}
}
