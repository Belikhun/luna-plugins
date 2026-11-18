package dev.belikhun.luna.core.velocity.network.firewall;

import dev.belikhun.luna.core.api.heartbeat.HeartbeatFormCodec;
import dev.belikhun.luna.core.api.http.HttpRequest;
import dev.belikhun.luna.core.api.http.HttpResponse;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.http.RequestAuthorizer;
import dev.belikhun.luna.core.api.http.Router;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.network.NetworkModeration;
import dev.belikhun.luna.core.velocity.network.VelocityNetworkBanStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The firewall's management surface for the console: read the rules, the
 * settings and the countries held, change them, read the activity, check an
 * address, and read the ban history.
 *
 * Bodies are form-encoded, as every other LunaCore POST is. Lists travel as
 * text: a rule's addresses one per line, its countries comma-separated, a
 * country's prefixes one per line.
 */
public final class VelocityFirewallHttpEndpoints {
	private static final long DAY = 24L * 3_600_000;

	private final LunaLogger logger;
	private final VelocityFirewall firewall;
	private final VelocityNetworkBanStore bans;
	private final RequestAuthorizer authorizer;

	public VelocityFirewallHttpEndpoints(LunaLogger logger, VelocityFirewall firewall, NetworkModeration moderation, RequestAuthorizer authorizer) {
		this.logger = logger.scope("FirewallHttp");
		this.firewall = firewall;
		this.bans = moderation.ipBans();
		this.authorizer = authorizer;
	}

	public void register(Router router) {
		router.get("/network/firewall", request -> guarded(request, "/network/firewall", () -> {
			long startedAt = System.nanoTime();
			long now = System.currentTimeMillis();
			List<Map<String, Object>> rules = new ArrayList<>();

			for (FirewallRule rule : firewall.rules()) {
				rules.add(ruleRow(rule));
			}

			int automatic = 0;
			List<VelocityNetworkBanStore.Entry> active = bans.list();

			for (VelocityNetworkBanStore.Entry entry : active) {
				if (entry.automatic()) {
					automatic++;
				}
			}

			long refused = 0;

			for (Map<String, Object> row : firewall.hourlyCounts(now - DAY, now + 1)) {
				refused += (long) row.get("count");
			}

			Map<String, Object> stats = new LinkedHashMap<>();

			stats.put("activeBans", active.size());
			stats.put("automaticBans", automatic);
			stats.put("refused24h", refused);

			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("available", firewall.available());
			payload.put("settings", settingsRow(firewall.settings()));
			payload.put("protectedRanges", FirewallSettings.PROTECTED_RANGES);
			payload.put("rules", rules);
			payload.put("countries", firewall.countries());
			payload.put("stats", stats);
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/network/firewall/settings", request -> guarded(request, "/network/firewall/settings", () -> {
			long startedAt = System.nanoTime();

			if (!firewall.available()) {
				return LunaJson.error(503, "firewall database is not available");
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());

			body.remove("actor");

			FirewallSettings next = firewall.updateSettings(body);
			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("settings", settingsRow(next));
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/network/firewall/rules", request -> guarded(request, "/network/firewall/rules", () -> {
			long startedAt = System.nanoTime();

			if (!firewall.available()) {
				return LunaJson.error(503, "firewall database is not available");
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String action = body.getOrDefault("action", "").trim();
			String id = body.getOrDefault("id", "").trim();
			Map<String, Object> payload = new LinkedHashMap<>();

			switch (action) {
				case "save" -> {
					String failure = firewall.saveRule(
						id.isEmpty() ? null : id,
						body.getOrDefault("rule", "").trim(),
						!"false".equalsIgnoreCase(body.getOrDefault("enabled", "true").trim()),
						lines(body.getOrDefault("addresses", ""), "\n"),
						lines(body.getOrDefault("countries", ""), ","),
						body.getOrDefault("note", "").trim(),
						body.getOrDefault("actor", "").trim()
					);

					if (failure != null) {
						return LunaJson.error(400, failure);
					}

					payload.put("saved", true);
				}
				case "enable", "disable" -> payload.put("changed", firewall.setRuleEnabled(id, "enable".equals(action)));
				case "remove" -> payload.put("removed", firewall.removeRule(id));
				default -> {
					return LunaJson.error(400, "action must be save, enable, disable or remove");
				}
			}

			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.post("/network/firewall/countries", request -> guarded(request, "/network/firewall/countries", () -> {
			long startedAt = System.nanoTime();

			if (!firewall.available()) {
				return LunaJson.error(503, "firewall database is not available");
			}

			Map<String, String> body = HeartbeatFormCodec.decode(request.body());
			String code = body.getOrDefault("code", "").trim();

			if (!code.matches("^[A-Za-z]{2}$")) {
				return LunaJson.error(400, "code must be a two-letter country code");
			}

			if ("remove".equals(body.getOrDefault("action", "").trim())) {
				firewall.removeCountry(code);
			} else {
				firewall.putCountry(code, body.getOrDefault("release", "").trim(), body.getOrDefault("prefixes", ""));
			}

			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("countries", firewall.countries());
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.get("/network/firewall/activity", request -> guarded(request, "/network/firewall/activity", () -> {
			long startedAt = System.nanoTime();
			long now = System.currentTimeMillis();
			long from = parseLong(request.queryParam("from", ""), now - DAY);
			long to = parseLong(request.queryParam("to", ""), now + 1);
			int limit = (int) Math.max(1, Math.min(2000, parseLong(request.queryParam("limit", ""), 200)));
			String ip = request.queryParam("ip", "").trim();
			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("from", from);
			payload.put("to", to);
			payload.put("hourly", firewall.hourlyCounts(from, to, ip));
			payload.put("recent", firewall.recentRefusals(from, to, ip, limit));
			payload.put("top", firewall.topRefused(from, to, ip, 20));
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.get("/network/firewall/check", request -> guarded(request, "/network/firewall/check", () -> {
			long startedAt = System.nanoTime();
			VelocityFirewall.Verdict verdict = firewall.check(request.queryParam("ip", ""));
			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("ip", verdict.ip());
			payload.put("valid", verdict.valid());
			payload.put("protected", verdict.protectedAddress());
			payload.put("ban", verdict.ban() == null ? null : NetworkModeration.ipBanRow(verdict.ban()));
			payload.put("allowedBy", verdict.allowedBy() == null ? null : ruleRow(verdict.allowedBy()));

			List<Map<String, Object>> denied = new ArrayList<>();

			for (FirewallRule rule : verdict.deniedBy()) {
				denied.add(ruleRow(rule));
			}

			payload.put("deniedBy", denied);
			payload.put("countries", verdict.countries());
			payload.put("enabled", firewall.settings().enabled());
			payload.put("outcome", verdict.outcome());
			payload.put("strikes", verdict.valid() ? bans.nextStrike(verdict.ip()) - 1 : 0);
			return LunaJson.envelope(200, payload, startedAt);
		}));

		router.get("/network/ip-bans/history", request -> guarded(request, "/network/ip-bans/history", () -> {
			long startedAt = System.nanoTime();

			if (!bans.available()) {
				return LunaJson.error(503, "network ban database is not available");
			}

			int limit = (int) Math.max(1, Math.min(5000, parseLong(request.queryParam("limit", ""), 500)));
			List<Map<String, Object>> rows = new ArrayList<>();

			for (VelocityNetworkBanStore.Ended ended : bans.history(request.queryParam("ip", ""), limit)) {
				Map<String, Object> row = new LinkedHashMap<>();

				row.put("ip", ended.ip());
				row.put("reason", ended.reason());
				row.put("actor", ended.actor());
				row.put("source", ended.source());
				row.put("strikes", ended.strikes());
				row.put("createdAtEpochMillis", ended.createdAt());
				row.put("expiresAtEpochMillis", ended.expiresAt());
				row.put("hits", ended.hits());
				row.put("endedAtEpochMillis", ended.endedAt());
				row.put("ended", ended.ended());
				row.put("endedBy", ended.endedBy());
				rows.add(row);
			}

			Map<String, Object> payload = new LinkedHashMap<>();

			payload.put("total", rows.size());
			payload.put("bans", rows);
			return LunaJson.envelope(200, payload, startedAt);
		}));
	}

	private interface Handler {
		HttpResponse handle();
	}

	private HttpResponse guarded(HttpRequest request, String path, Handler handler) {
		if (!authorizer.authorized(request)) {
			logger.warn("Từ chối truy vấn " + path + " do sai token hoặc thiếu token.");
			return authorizer.unauthorized();
		}

		try {
			return handler.handle();
		} catch (RuntimeException exception) {
			logger.error("Lỗi khi xử lý " + path + ": " + exception.getMessage(), exception);
			return LunaJson.error(500, exception.getMessage() == null ? "internal error" : exception.getMessage());
		}
	}

	static Map<String, Object> ruleRow(FirewallRule rule) {
		Map<String, Object> row = new LinkedHashMap<>();

		row.put("id", rule.id());
		row.put("action", rule.action());
		row.put("enabled", rule.enabled());
		row.put("addresses", rule.addresses());
		row.put("countries", rule.countries());
		row.put("note", rule.note());
		row.put("createdBy", rule.createdBy());
		row.put("createdAtEpochMillis", rule.createdAt());
		row.put("updatedAtEpochMillis", rule.updatedAt());
		row.put("hits", rule.hits());
		row.put("lastHitAtEpochMillis", rule.lastHitAt());
		return row;
	}

	private static Map<String, Object> settingsRow(FirewallSettings settings) {
		Map<String, Object> row = new LinkedHashMap<>();

		row.put("enabled", settings.enabled());
		row.put("loginFlood", settings.loginFlood());
		row.put("loginFloodPerMinute", settings.loginFloodPerMinute());
		row.put("pingScan", settings.pingScan());
		row.put("pingScanCount", settings.pingScanCount());
		row.put("pingScanMinutes", settings.pingScanMinutes());
		row.put("suspiciousNames", settings.suspiciousNames());
		row.put("namePatterns", settings.namePatterns());
		row.put("failedLogins", settings.failedLogins());
		row.put("failedLoginCount", settings.failedLoginCount());
		row.put("failedLoginMinutes", settings.failedLoginMinutes());
		row.put("hidePingFromRefused", settings.hidePingFromRefused());
		row.put("banMinutes", settings.banMinutes());
		row.put("repeatMultiplier", settings.repeatMultiplier());
		row.put("maxBanDays", settings.maxBanDays());
		row.put("protect", settings.protect());
		return row;
	}

	private static List<String> lines(String text, String separator) {
		List<String> out = new ArrayList<>();

		for (String part : Arrays.asList(text.split(separator))) {
			String trimmed = part.trim();

			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}

		return out;
	}

	private static long parseLong(String text, long fallback) {
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}
}
