package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.tabbridge.VelocityTabBridgeObserver;
import dev.belikhun.luna.core.velocity.tablist.TabListCapture;
import dev.belikhun.luna.core.velocity.tablist.VelocityTabListRenderer;

import java.util.Optional;
import java.util.UUID;

/**
 * The placeholder inspection tools over HTTP, token-gated like every other route.
 *
 * {@code /tab/{player}} answers JSON by default and text when
 * {@code ?format=plain|mini|legacy} is given, so both the console and a shell
 * can read what a player sees.
 */
public final class VelocityPlaceholderDebugHttpEndpoints {
	private static final int DEFAULT_RECENT = 20;
	private static final int MAX_RECENT = 64;

	private final LunaLogger logger;
	private final ProxyServer proxyServer;
	private final VelocityTabListRenderer tabListRenderer;
	private final VelocityTabBridgeObserver bridgeObserver;
	private final RequestAuthorizer authorizer;

	public VelocityPlaceholderDebugHttpEndpoints(
		LunaLogger logger,
		ProxyServer proxyServer,
		VelocityTabListRenderer tabListRenderer,
		VelocityTabBridgeObserver bridgeObserver,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("PlaceholderHttp");
		this.proxyServer = proxyServer;
		this.tabListRenderer = tabListRenderer;
		this.bridgeObserver = bridgeObserver;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/tab/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /tab/{player} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "");
			Player viewer = findPlayer(reference).orElse(null);

			if (viewer == null) {
				return LunaJson.error(404, "player not online: " + reference);
			}

			TabListCapture capture = tabListRenderer.capture(viewer);
			String format = request.queryParam("format", "");

			if (format.isBlank() || format.equalsIgnoreCase("json")) {
				return LunaJson.envelope(200, tabListRenderer.toJson(capture), startedAt);
			}

			String body = String.join("\n", tabListRenderer.renderLines(capture, VelocityTabListRenderer.Format.parse(format)));
			return HttpResponse.text(200, body + "\n");
		});

		router.get("/placeholders/bridge", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /placeholders/bridge do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			return LunaJson.envelope(200, bridgeObserver.toJson(), startedAt);
		});

		router.get("/placeholders/bridge/{player}", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /placeholders/bridge/{player} do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			String reference = request.pathParam("player", "");
			UUID playerId = findPlayer(reference).map(Player::getUniqueId).orElseGet(() -> parseUuid(reference));

			if (playerId == null) {
				return LunaJson.error(404, "unknown player: " + reference);
			}

			int limit = DEFAULT_RECENT;

			try {
				limit = Math.min(MAX_RECENT, Math.max(1, Integer.parseInt(request.queryParam("limit", String.valueOf(DEFAULT_RECENT)))));
			} catch (NumberFormatException ignored) {
				limit = DEFAULT_RECENT;
			}

			return LunaJson.envelope(200, bridgeObserver.playerJson(playerId, limit), startedAt);
		});
	}

	private Optional<Player> findPlayer(String reference) {
		if (reference == null || reference.isBlank()) {
			return Optional.empty();
		}

		Optional<Player> byName = proxyServer.getPlayer(reference);

		if (byName.isPresent()) {
			return byName;
		}

		UUID uuid = parseUuid(reference);

		if (uuid == null) {
			return Optional.empty();
		}

		return proxyServer.getPlayer(uuid);
	}

	private UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
