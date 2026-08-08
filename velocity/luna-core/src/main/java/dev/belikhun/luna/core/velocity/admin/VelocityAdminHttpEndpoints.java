package dev.belikhun.luna.core.velocity.admin;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerHttpEndpoints;
import dev.belikhun.luna.core.velocity.players.VelocityPlayerRecordStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Administrative actions the control console can take on the network.
 *
 * These are the write half of the console API: until now the console could only
 * stuff text into a screen session and hope, with no way to read a result. Every
 * route here reports what actually happened — a command's own output, how many
 * players a broadcast reached, whether a transfer was accepted.
 *
 * Bodies are {@code application/x-www-form-urlencoded}, matching the transport the
 * heartbeat endpoints already use, so no JSON parser is needed on either side.
 * Every route is token-gated, and all of them are audit-logged: these change the
 * live network.
 */
public final class VelocityAdminHttpEndpoints {
	/** How long a proxy command may run before the caller gets a timeout. */
	private static final long COMMAND_TIMEOUT_MILLIS = 5000L;

	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityPlayerHttpEndpoints playerEndpoints;
	private final VelocityPlayerRecordStore recordStore;
	private final RequestAuthorizer authorizer;

	public VelocityAdminHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityPlayerHttpEndpoints playerEndpoints,
		VelocityPlayerRecordStore recordStore,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("AdminHttp");
		this.proxyServer = proxyServer;
		this.playerEndpoints = playerEndpoints;
		this.recordStore = recordStore;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.post("/admin/command", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /admin/command do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String commandLine = stripSlash(body.getOrDefault("command", ""));

			if (commandLine.isBlank()) {
				return LunaJson.error(400, "command is required");
			}

			return runCommand(commandLine, startedAt);
		});

		router.post("/admin/broadcast", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối POST /admin/broadcast do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String message = body.getOrDefault("message", "");
			String serverFilter = normalize(body.getOrDefault("server", ""));

			if (message.isBlank()) {
				return LunaJson.error(400, "message is required");
			}

			Component component = render(message);
			int reached = 0;

			for (Player player : proxyServer.getAllPlayers()) {
				if (!serverFilter.isBlank() && !serverFilter.equals(currentServer(player))) {
					continue;
				}

				player.sendMessage(component);
				reached++;
			}

			logger.audit("Console broadcast tới " + reached + " người chơi"
				+ (serverFilter.isBlank() ? "" : " trên " + serverFilter) + ": " + message);

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("reached", reached);
			payload.put("server", serverFilter);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.post("/admin/players/{player}/kick", request -> {
			return withPlayer(request, "kick", (player, body, startedAt) -> {
				String reason = body.getOrDefault("reason", "");
				Component component = reason.isBlank()
					? Component.text("Bạn đã bị ngắt kết nối bởi quản trị viên.")
					: render(reason);

				player.disconnect(component);
				logger.audit("Console kick " + player.getUsername() + ": " + reason);
				recordStore.recordModeration(
					player.getUniqueId().toString(),
					player.getUsername(),
					"kick",
					"console",
					reason,
					"",
					""
				);

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("username", player.getUsername());
				payload.put("uuid", player.getUniqueId().toString());
				payload.put("reason", reason);
				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		router.post("/admin/players/{player}/message", request -> {
			return withPlayer(request, "message", (player, body, startedAt) -> {
				String message = body.getOrDefault("message", "");

				if (message.isBlank()) {
					return LunaJson.error(400, "message is required");
				}

				player.sendMessage(render(message));
				logger.audit("Console gửi tin nhắn tới " + player.getUsername() + ": " + message);

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("username", player.getUsername());
				payload.put("uuid", player.getUniqueId().toString());
				return LunaJson.envelope(200, payload, startedAt);
			});
		});

		router.post("/admin/players/{player}/transfer", request -> {
			return withPlayer(request, "transfer", (player, body, startedAt) -> {
				String target = normalize(body.getOrDefault("server", ""));

				if (target.isBlank()) {
					return LunaJson.error(400, "server is required");
				}

				Optional<RegisteredServer> server = proxyServer.getServer(target);

				if (server.isEmpty()) {
					return LunaJson.error(404, "server not registered on the proxy: " + target);
				}

				// The connection request resolves after the handshake, so this reports what
				// the target backend actually decided rather than that we asked.
				try {
					var result = player.createConnectionRequest(server.get())
						.connect()
						.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

					logger.audit("Console chuyển " + player.getUsername() + " sang " + target
						+ " → " + result.getStatus());

					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("username", player.getUsername());
					payload.put("uuid", player.getUniqueId().toString());
					payload.put("server", target);
					payload.put("status", result.getStatus().name());
					payload.put("successful", result.isSuccessful());
					payload.put("reason", result.getReasonComponent()
						.map(reason -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
							.plainText().serialize(reason))
						.orElse(""));
					return LunaJson.envelope(200, payload, startedAt);
				} catch (TimeoutException timeout) {
					return LunaJson.error(504, "transfer did not complete within "
						+ COMMAND_TIMEOUT_MILLIS + "ms");
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return LunaJson.error(500, "transfer interrupted");
				} catch (ExecutionException failure) {
					logger.error("Chuyển server thất bại: " + failure.getMessage(), failure);
					return LunaJson.error(500, "transfer failed: " + rootMessage(failure));
				}
			});
		});
	}

	/** Shared shape for the per-player routes: resolve, decode the body, act. */
	private HttpResponse withPlayer(HttpRequest request, String action, PlayerAction handler) {
		if (!authorizer.authorized(request)) {
			logger.warn("Từ chối POST /admin/players/.../" + action + " do sai token hoặc thiếu token.");
			return authorizer.unauthorized();
		}

		long startedAt = System.nanoTime();
		String reference = request.pathParam("player", "").trim();
		Optional<Player> found = playerEndpoints.resolvePlayer(reference);

		if (found.isEmpty()) {
			return LunaJson.error(404, "player not connected: " + reference);
		}

		return handler.apply(found.get(), HeartbeatFormCodec.decode(request.body()), startedAt);
	}

	@FunctionalInterface
	private interface PlayerAction {
		HttpResponse apply(Player player, Map<String, String> body, long startedAt);
	}

	private HttpResponse runCommand(String commandLine, long startedAt) {
		CapturingCommandSource source = new CapturingCommandSource();
		logger.audit("Console chạy lệnh proxy: " + commandLine);

		try {
			boolean handled = proxyServer.getCommandManager()
				.executeImmediatelyAsync(source, commandLine)
				.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

			List<String> output = new ArrayList<>(source.output());

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("command", commandLine);
			payload.put("handled", handled);
			payload.put("output", output);
			return LunaJson.envelope(200, payload, startedAt);
		} catch (TimeoutException timeout) {
			// The command may still be running; report what it printed before the cut-off.
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("error", "command did not finish within " + COMMAND_TIMEOUT_MILLIS + "ms");
			payload.put("command", commandLine);
			payload.put("output", new ArrayList<>(source.output()));
			return LunaJson.envelope(504, payload, startedAt);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return LunaJson.error(500, "command interrupted");
		} catch (ExecutionException failure) {
			logger.error("Lệnh proxy thất bại: " + failure.getMessage(), failure);
			return LunaJson.error(500, "command failed: " + rootMessage(failure));
		}
	}

	/** Console callers naturally type a leading slash; the dispatcher does not want one. */
	private String stripSlash(String commandLine) {
		String trimmed = commandLine == null ? "" : commandLine.trim();
		return trimmed.startsWith("/") ? trimmed.substring(1).trim() : trimmed;
	}

	/** Render a message as MiniMessage, falling back to literal text if it will not parse. */
	private Component render(String message) {
		try {
			return MINI_MESSAGE.deserialize(message);
		} catch (RuntimeException notMiniMessage) {
			return Component.text(message);
		}
	}

	private String currentServer(Player player) {
		return player.getCurrentServer()
			.map(connection -> normalize(connection.getServerInfo().getName()))
			.orElse("");
	}

	private String rootMessage(Throwable throwable) {
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
