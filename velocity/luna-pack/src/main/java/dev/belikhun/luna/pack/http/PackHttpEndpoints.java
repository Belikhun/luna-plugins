package dev.belikhun.luna.pack.http;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;
import dev.belikhun.luna.pack.model.PlayerPackSession;
import dev.belikhun.luna.pack.model.ResolvedPack;
import dev.belikhun.luna.pack.service.PackCatalogService;
import dev.belikhun.luna.pack.service.PlayerPackSessionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the pack loader for the Luna control console.
 *
 * The proxy is the only place that knows two things the packs directory cannot
 * tell anyone: what it actually resolved each definition to (URL, hash, size,
 * or why it could not), and which players are holding which pack right now.
 * Both are exposed here on LunaCore's HTTP server, token-gated like every other
 * dashboard route — the payload names players and their current backend.
 */
public final class PackHttpEndpoints {
	private final LunaLogger logger;
	private final ProxyServer server;
	private final PackCatalogService catalogService;
	private final PlayerPackSessionStore sessionStore;
	private final RequestAuthorizer authorizer;

	public PackHttpEndpoints(
		LunaLogger logger,
		ProxyServer server,
		PackCatalogService catalogService,
		PlayerPackSessionStore sessionStore,
		RequestAuthorizer authorizer
	) {
		this.logger = logger.scope("PackHttp");
		this.server = server;
		this.catalogService = catalogService;
		this.sessionStore = sessionStore;
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/packs/catalog", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /packs/catalog do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();
			PackCatalogSnapshot snapshot = catalogService.snapshot();
			PackReloadReport report = snapshot.report();

			List<Map<String, Object>> packs = new ArrayList<>();
			for (ResolvedPack resolved : snapshot.resolvedPacks()) {
				packs.add(buildResolved(resolved));
			}

			Map<String, Object> counts = new LinkedHashMap<>();
			counts.put("discoveredFiles", report.discoveredFiles());
			counts.put("validDefinitions", report.validDefinitions());
			counts.put("invalidDefinitions", report.invalidDefinitions());
			counts.put("resolvedAvailable", report.resolvedAvailable());
			counts.put("resolvedMissingFiles", report.resolvedMissingFiles());
			counts.put("resolvedInvalidUrls", report.resolvedInvalidUrls());

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("report", counts);
			payload.put("packs", packs);
			return LunaJson.envelope(200, payload, startedAt);
		});

		router.get("/packs/sessions", request -> {
			if (!authorizer.authorized(request)) {
				logger.warn("Từ chối truy vấn /packs/sessions do sai token hoặc thiếu token.");
				return authorizer.unauthorized();
			}

			long startedAt = System.nanoTime();

			List<Map<String, Object>> players = new ArrayList<>();
			for (Player player : server.getAllPlayers()) {
				players.add(buildSession(player));
			}

			players.sort((left, right) -> String.valueOf(left.get("username"))
				.compareToIgnoreCase(String.valueOf(right.get("username"))));

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("generatedAtEpochMillis", System.currentTimeMillis());
			payload.put("onlineCount", players.size());
			payload.put("players", players);
			return LunaJson.envelope(200, payload, startedAt);
		});
	}

	private Map<String, Object> buildResolved(ResolvedPack resolved) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", resolved.definition().name());
		row.put("normalizedName", resolved.definition().normalizedName());
		row.put("filename", resolved.definition().filename());
		row.put("priority", resolved.definition().priority());
		row.put("required", resolved.definition().required());
		row.put("enabled", resolved.definition().enabled());
		row.put("servers", new ArrayList<>(resolved.definition().servers()));
		row.put("url", resolved.url() == null ? "" : resolved.url().toString());
		row.put("sha1", resolved.sha1() == null ? "" : resolved.sha1());
		row.put("sizeBytes", resolved.sizeBytes());
		row.put("available", resolved.available());
		row.put("unavailableReason", resolved.unavailableReason() == null ? "" : resolved.unavailableReason());

		if (resolved.formatRange() != null) {
			Map<String, Object> formats = new LinkedHashMap<>();
			formats.put("min", resolved.formatRange().min().render());
			formats.put("max", resolved.formatRange().max().render());
			formats.put("source", resolved.formatRange().source());
			formats.put("clamped", resolved.formatRange().clamped());
			row.put("formats", formats);
		}

		return row;
	}

	/**
	 * One player's pack state. Pending packs are keyed by the pack id the client
	 * was sent, so the values — the pack names — are what the console wants.
	 */
	private Map<String, Object> buildSession(Player player) {
		PlayerPackSession session = sessionStore.get(player.getUniqueId());

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("uuid", player.getUniqueId().toString());
		row.put("username", player.getUsername());
		row.put("server", player.getCurrentServer()
			.map(connection -> connection.getServerInfo().getName())
			.orElse(""));
		row.put("protocol", player.getProtocolVersion().getProtocol());
		row.put("loaded", session == null ? List.of() : new ArrayList<>(session.loadedByName().keySet()));
		row.put("pending", session == null ? List.of() : new ArrayList<>(session.pendingByPackId().values()));
		row.put("lastFailure", session == null || session.lastFailure() == null ? "" : session.lastFailure());
		return row;
	}
}
