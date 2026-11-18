package dev.belikhun.luna.core.velocity.network.firewall;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.network.NetworkBanMessages;
import dev.belikhun.luna.core.velocity.network.NetworkModeration;
import dev.belikhun.luna.core.velocity.network.VelocityNetworkBanStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The network's firewall, enforced by the proxy when a player connects and when
 * a client pings the server list. Nothing here touches the kernel: a refused
 * connection has been accepted and read far enough to know its address, and is
 * then turned away with a message.
 *
 * A login is decided in this order:
 * <ol>
 *   <li>a network ban on the address (or a prefix holding it) refuses it;</li>
 *   <li>a protected address (loopback, private, the operator's list) passes;</li>
 *   <li>an enabled allow rule matching it lets it through, past the deny rules
 *       and the detectors;</li>
 *   <li>an enabled deny rule matching it refuses it;</li>
 *   <li>the detectors look at it: a login flood or a suspicious name bans the
 *       address on the spot.</li>
 * </ol>
 * A ping goes through the same ban and rule checks, and a refused address's
 * ping is answered with an empty entry when {@code hidePingFromRefused} is on;
 * an address that pings over and over without ever logging in is a scanner,
 * which the ping-scan detector bans. luna-auth reports wrong passwords through
 * {@link #reportFailedLogin}, which feeds the failed-login detector.
 *
 * An automatic ban goes through {@link NetworkModeration#banIp}, so it kicks
 * whoever is online from that address and lands in the moderation log like a
 * manual one; its length grows with each strike.
 *
 * Rules, settings and the countries' prefixes live in the shared database;
 * the console writes them over HTTP. Counters (rule hits, refusals per hour)
 * are kept in memory and written once a minute.
 */
public final class VelocityFirewall {
	/** How many addresses the detectors track at once; the quietest are dropped past it. */
	private static final int MAX_TRACKED = 50_000;

	/** How long refusals are kept as rows, and the hourly counts. */
	private static final long REFUSALS_KEEP_MS = 30L * 24 * 3_600_000;
	private static final long HOURLY_KEEP_MS = 400L * 24 * 3_600_000;

	private static final long MINUTE = 60_000L;
	private static final long HOUR = 3_600_000L;

	private final LunaLogger logger;
	private final VelocityNetworkBanStore bans;
	private final NetworkModeration moderation;
	private final ScheduledExecutorService worker;
	private final SecureRandom random = new SecureRandom();

	private volatile Database database;
	private volatile Compiled compiled;

	/** Per-address activity the detectors read. */
	private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();
	/** Addresses being banned right now, so two events in the same instant ban once. */
	private final ConcurrentHashMap<String, Boolean> banning = new ConcurrentHashMap<>();
	/** Rule hits since the last write, by rule id. */
	private final ConcurrentHashMap<String, AtomicLong> ruleHits = new ConcurrentHashMap<>();
	/** Refusals per hour and reason since the last write. */
	private final ConcurrentHashMap<String, AtomicLong> hourly = new ConcurrentHashMap<>();
	/** Refusals waiting to be written, one row per address, kind and reason a minute. */
	private final ConcurrentHashMap<String, Refusal> pending = new ConcurrentHashMap<>();

	/** The rules and settings as enforcement reads them; replaced whole on every change. */
	private record Compiled(
		FirewallSettings settings,
		List<FirewallRule> rules,
		Map<String, IpRanges> ruleAddresses,
		Map<String, IpRanges> countries,
		Map<String, String> countryReleases,
		IpRanges protect,
		List<Pattern> namePatterns
	) {
	}

	/** What one address has done lately. */
	private static final class Tracker {
		final ArrayDeque<Long> logins = new ArrayDeque<>();
		final ArrayDeque<Long> pings = new ArrayDeque<>();
		final ArrayDeque<Long> failures = new ArrayDeque<>();
		volatile long lastLoginAt;
		volatile long lastSeenAt;
	}

	/** One refusal row being gathered. */
	private static final class Refusal {
		final long at;
		final String ip;
		final String kind;
		final String reason;
		volatile String username;
		volatile String detail;
		final AtomicLong count = new AtomicLong();

		Refusal(long at, String ip, String kind, String reason, String username, String detail) {
			this.at = at;
			this.ip = ip;
			this.kind = kind;
			this.reason = reason;
			this.username = username;
			this.detail = detail;
		}
	}

	/** What happens to an address, as {@link #check} answers it. */
	public record Verdict(
		String ip,
		boolean valid,
		boolean protectedAddress,
		VelocityNetworkBanStore.Entry ban,
		FirewallRule allowedBy,
		List<FirewallRule> deniedBy,
		List<String> countries,
		String outcome
	) {
	}

	public VelocityFirewall(LunaLogger logger, VelocityNetworkBanStore bans, NetworkModeration moderation) {
		this.logger = logger.scope("Firewall");
		this.bans = bans;
		this.moderation = moderation;
		this.database = new NoopDatabase();
		this.compiled = compile(FirewallSettings.defaults(), List.of(), Map.of(), Map.of());
		this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "luna-firewall");
			thread.setDaemon(true);
			return thread;
		});
		this.worker.scheduleAtFixedRate(this::flushSafely, 1, 1, TimeUnit.MINUTES);
		this.worker.scheduleAtFixedRate(this::pruneSafely, 5, 360, TimeUnit.MINUTES);
	}

	// -- lifecycle ------------------------------------------------------------------------

	/** Swap the database handle on reload and read the rules, settings and countries again. */
	public void attach(Database nextDatabase) {
		this.database = nextDatabase;

		if (!available()) {
			return;
		}

		try {
			reload();
			logger.audit("Đã nạp tường lửa: " + compiled.rules().size() + " luật, " + compiled.countries().size() + " quốc gia.");
		} catch (RuntimeException exception) {
			logger.error("Không nạp được tường lửa: " + exception.getMessage(), exception);
		}
	}

	public boolean available() {
		return !(database instanceof NoopDatabase);
	}

	public void shutdown() {
		flushSafely();
		worker.shutdown();

		try {
			if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
				worker.shutdownNow();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			worker.shutdownNow();
		}
	}

	private void reload() {
		Map<String, String> settingRows = new LinkedHashMap<>();

		for (Map<String, Object> row : database.query("SELECT k, v FROM luna_firewall_settings", List.of())) {
			settingRows.put(String.valueOf(row.get("k")), row.get("v") == null ? "" : String.valueOf(row.get("v")));
		}

		List<FirewallRule> rules = new ArrayList<>();

		for (Map<String, Object> row : database.query("SELECT * FROM luna_firewall_rules ORDER BY created_at", List.of())) {
			rules.add(ruleOf(row));
		}

		Map<String, String> prefixes = new LinkedHashMap<>();
		Map<String, String> releases = new LinkedHashMap<>();

		for (Map<String, Object> row : database.query("SELECT code, release_tag, prefixes FROM luna_firewall_countries", List.of())) {
			String code = String.valueOf(row.get("code"));

			prefixes.put(code, row.get("prefixes") == null ? "" : String.valueOf(row.get("prefixes")));
			releases.put(code, String.valueOf(row.get("release_tag")));
		}

		compiled = compile(FirewallSettings.fromMap(settingRows), rules, prefixes, releases);
	}

	private Compiled compile(FirewallSettings settings, List<FirewallRule> rules, Map<String, String> countryPrefixes, Map<String, String> releases) {
		Map<String, IpRanges> ruleAddresses = new LinkedHashMap<>();

		for (FirewallRule rule : rules) {
			ruleAddresses.put(rule.id(), IpRanges.of(rule.addresses()));
		}

		Map<String, IpRanges> countries = new LinkedHashMap<>();

		for (Map.Entry<String, String> entry : countryPrefixes.entrySet()) {
			countries.put(entry.getKey(), IpRanges.of(Arrays.asList(entry.getValue().split("\n"))));
		}

		List<String> protect = new ArrayList<>(FirewallSettings.PROTECTED_RANGES);

		protect.addAll(settings.protect());

		return new Compiled(
			settings,
			List.copyOf(rules),
			ruleAddresses,
			countries,
			Map.copyOf(releases),
			IpRanges.of(protect),
			settings.compiledNamePatterns()
		);
	}

	// -- enforcement ------------------------------------------------------------------------

	/**
	 * The enforcement point for logins. FIRST order, so a refused address is
	 * turned away before luna-auth, the record store or anything else spends
	 * work on it.
	 */
	@Subscribe(order = PostOrder.FIRST)
	public void onPreLogin(PreLoginEvent event) {
		String ip = addressOf(event.getConnection().getRemoteAddress());

		if (ip == null) {
			return;
		}

		String username = event.getUsername() == null ? "" : event.getUsername();
		long now = System.currentTimeMillis();
		VelocityNetworkBanStore.Entry ban = bans.activeBan(ip, now);

		if (ban != null) {
			bans.recordHit(ban, now);
			refuse(now, ip, "login", "ban", username, ban.ip);
			event.setResult(PreLoginEvent.PreLoginComponentResult.denied(NetworkBanMessages.ipBanned(ban.reason, ban.expiresAt)));
			return;
		}

		Compiled state = compiled;

		if (!state.settings().enabled()) {
			return;
		}

		IpRanges.Address address = IpRanges.parseAddress(ip);

		if (state.protect().contains(address)) {
			return;
		}

		FirewallRule allow = firstMatch(state, address, true);

		if (allow != null) {
			hit(allow);
			return;
		}

		FirewallRule deny = firstMatch(state, address, false);

		if (deny != null) {
			hit(deny);
			refuse(now, ip, "login", "rule", username, deny.id());
			event.setResult(PreLoginEvent.PreLoginComponentResult.denied(deniedMessage()));
			return;
		}

		Tracker tracker = tracker(ip, now);
		String detector = null;
		String detail = "";

		synchronized (tracker) {
			tracker.lastLoginAt = now;
			trim(tracker.logins, now - MINUTE);
			tracker.logins.addLast(now);

			if (state.settings().loginFlood() && tracker.logins.size() > state.settings().loginFloodPerMinute()) {
				detector = "loginFlood";
				detail = tracker.logins.size() + "/min";
			}
		}

		if (detector == null && state.settings().suspiciousNames()) {
			for (Pattern pattern : state.namePatterns()) {
				if (pattern.matcher(username).find()) {
					detector = "suspiciousNames";
					detail = username;
					break;
				}
			}
		}

		if (detector == null) {
			return;
		}

		VelocityNetworkBanStore.Entry made = autoBan(ip, detector, detail, now);

		refuse(now, ip, "login", detector, username, detail);
		event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
			made == null
				? deniedMessage()
				: NetworkBanMessages.ipBanned(made.reason, made.expiresAt)
		));
	}

	/** Server-list pings: refused addresses get an empty entry, and scanners are found here. */
	@Subscribe(order = PostOrder.FIRST)
	public void onPing(ProxyPingEvent event) {
		String ip = addressOf(event.getConnection().getRemoteAddress());

		if (ip == null) {
			return;
		}

		long now = System.currentTimeMillis();
		Compiled state = compiled;
		VelocityNetworkBanStore.Entry ban = bans.activeBan(ip, now);
		String refusedFor = null;
		String detail = "";

		if (ban != null) {
			refusedFor = "ban";
			detail = ban.ip;
		} else if (state.settings().enabled()) {
			IpRanges.Address address = IpRanges.parseAddress(ip);

			if (state.protect().contains(address) || firstMatch(state, address, true) != null) {
				return;
			}

			FirewallRule deny = firstMatch(state, address, false);

			if (deny != null) {
				refusedFor = "rule";
				detail = deny.id();
			} else if (state.settings().pingScan()) {
				Tracker tracker = tracker(ip, now);
				boolean scanning;
				int count;

				synchronized (tracker) {
					long window = state.settings().pingScanMinutes() * MINUTE;

					trim(tracker.pings, now - window);
					tracker.pings.addLast(now);
					count = tracker.pings.size();
					scanning = count > state.settings().pingScanCount() && now - tracker.lastLoginAt > window;
				}

				if (scanning) {
					autoBan(ip, "pingScan", count + " pings", now);
					refusedFor = "pingScan";
					detail = count + " pings";
				}
			}
		}

		if (refusedFor == null) {
			return;
		}

		refuse(now, ip, "ping", refusedFor, "", detail);

		if (state.settings().hidePingFromRefused()) {
			ServerPing ping = event.getPing();

			// an empty entry: no player count, no sample, no message of the day
			event.setPing(ping.asBuilder()
				.onlinePlayers(0)
				.maximumPlayers(0)
				.clearSamplePlayers()
				.description(Component.empty())
				.build());
		}
	}

	/**
	 * A wrong password at luna's own login, reported by luna-auth. Several from one
	 * address, across any accounts, within the window ban it.
	 */
	public void reportFailedLogin(String ip, String username) {
		String key = IpRanges.normalize(ip);

		if (key == null) {
			return;
		}

		Compiled state = compiled;

		if (!state.settings().enabled() || !state.settings().failedLogins()) {
			return;
		}

		IpRanges.Address address = IpRanges.parseAddress(key);

		if (state.protect().contains(address) || firstMatch(state, address, true) != null) {
			return;
		}

		long now = System.currentTimeMillis();
		Tracker tracker = tracker(key, now);
		int count;

		synchronized (tracker) {
			trim(tracker.failures, now - state.settings().failedLoginMinutes() * MINUTE);
			tracker.failures.addLast(now);
			count = tracker.failures.size();
		}

		if (count > state.settings().failedLoginCount()) {
			autoBan(key, "failedLogins", count + " wrong passwords, last as " + username, now);
		}
	}

	/** What would happen to an address, for the console's check. */
	public Verdict check(String text) {
		String ip = IpRanges.normalize(text);

		if (ip == null) {
			return new Verdict(text, false, false, null, null, List.of(), List.of(), "invalid");
		}

		Compiled state = compiled;
		IpRanges.Address address = IpRanges.parseAddress(ip);
		VelocityNetworkBanStore.Entry ban = bans.activeBan(ip, System.currentTimeMillis());
		boolean isProtected = state.protect().contains(address);
		FirewallRule allow = firstMatch(state, address, true);
		List<FirewallRule> denies = new ArrayList<>();

		for (FirewallRule rule : state.rules()) {
			if (!rule.allows() && rule.enabled() && matches(state, rule, address)) {
				denies.add(rule);
			}
		}

		List<String> countries = new ArrayList<>();

		for (Map.Entry<String, IpRanges> entry : state.countries().entrySet()) {
			if (entry.getValue().contains(address)) {
				countries.add(entry.getKey());
			}
		}

		String outcome = ban != null
			? "banned"
			: !state.settings().enabled() || isProtected
				? "allowed"
				: allow != null
					? "allowed"
					: !denies.isEmpty()
						? "denied"
						: "allowed";

		return new Verdict(ip, true, isProtected, ban, allow, denies, countries, outcome);
	}

	// -- rules, settings, countries (the console's writes) --------------------------------

	public FirewallSettings settings() {
		return compiled.settings();
	}

	/** The rules with their hit counts, the ones not yet written included. */
	public List<FirewallRule> rules() {
		List<FirewallRule> out = new ArrayList<>();

		for (FirewallRule rule : compiled.rules()) {
			AtomicLong unwritten = ruleHits.get(rule.id());

			out.add(unwritten == null ? rule : rule.withHits(rule.hits() + unwritten.get(), rule.lastHitAt()));
		}

		return out;
	}

	/** Each country the database holds prefixes for: its code, release and prefix count. */
	public Map<String, Map<String, Object>> countries() {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		Compiled state = compiled;

		for (Map.Entry<String, IpRanges> entry : state.countries().entrySet()) {
			Map<String, Object> row = new LinkedHashMap<>();

			row.put("release", state.countryReleases().getOrDefault(entry.getKey(), ""));
			row.put("prefixes", entry.getValue().size());
			out.put(entry.getKey(), row);
		}

		return out;
	}

	/** Replace the settings with these values over the current ones; answers the result. */
	public synchronized FirewallSettings updateSettings(Map<String, String> values) {
		Map<String, String> merged = new LinkedHashMap<>(compiled.settings().toMap());

		merged.putAll(values);

		FirewallSettings next = FirewallSettings.fromMap(merged);

		for (Map.Entry<String, String> entry : next.toMap().entrySet()) {
			database.update("REPLACE INTO luna_firewall_settings (k, v) VALUES (?, ?)", List.of(entry.getKey(), entry.getValue()));
		}

		reload();
		logger.audit("Đã đổi cài đặt tường lửa.");
		return next;
	}

	/**
	 * Add a rule, or replace the one with this id. Answers an error message, or
	 * null when the rule was stored.
	 */
	public synchronized String saveRule(String id, String action, boolean enabled, List<String> addresses, List<String> countries, String note, String actor) {
		if (!"allow".equals(action) && !"deny".equals(action)) {
			return "action must be allow or deny";
		}

		List<String> cleanAddresses = new ArrayList<>();

		for (String text : addresses) {
			IpRanges.Range range = IpRanges.parseRange(text);

			if (range == null) {
				return "not an address or prefix: " + text;
			}

			cleanAddresses.add(range.text());
		}

		List<String> cleanCountries = new ArrayList<>();

		for (String code : countries) {
			String upper = code.trim().toUpperCase(Locale.ROOT);

			if (!upper.matches("^[A-Z]{2}$")) {
				return "not a country code: " + code;
			}

			cleanCountries.add(upper);
		}

		if (cleanAddresses.isEmpty() && cleanCountries.isEmpty()) {
			return "a rule needs addresses, countries or both";
		}

		long now = System.currentTimeMillis();
		FirewallRule existing = id == null
			? null
			: compiled.rules().stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
		String ruleId = existing == null
			? newId()
			: existing.id();

		database.update(
			"REPLACE INTO luna_firewall_rules (id, action, enabled, addresses, countries, note, created_by, created_at, updated_at, hits, last_hit_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			List.of(
				ruleId,
				action,
				enabled ? 1 : 0,
				String.join("\n", cleanAddresses),
				String.join(",", cleanCountries),
				note == null ? "" : note,
				existing == null ? (actor == null ? "" : actor) : existing.createdBy(),
				existing == null ? now : existing.createdAt(),
				now,
				existing == null ? 0L : existing.hits(),
				existing == null ? 0L : existing.lastHitAt()
			)
		);
		reload();
		logger.audit((existing == null ? "Thêm" : "Sửa") + " luật tường lửa " + ruleId + " (" + action + ") bởi " + actor);
		return null;
	}

	/** Turn a rule on or off; answers whether it existed. */
	public synchronized boolean setRuleEnabled(String id, boolean enabled) {
		int changed = database.update(
			"UPDATE luna_firewall_rules SET enabled = ?, updated_at = ? WHERE id = ?",
			List.of(enabled ? 1 : 0, System.currentTimeMillis(), id)
		);

		reload();
		return changed > 0;
	}

	/** Remove a rule; answers whether it existed. */
	public synchronized boolean removeRule(String id) {
		int changed = database.update("DELETE FROM luna_firewall_rules WHERE id = ?", List.of(id));

		ruleHits.remove(id);
		reload();
		return changed > 0;
	}

	/** Store a country's prefixes (one per line), as the console's GeoIP database computes them. */
	public synchronized void putCountry(String code, String release, String prefixes) {
		database.update(
			"REPLACE INTO luna_firewall_countries (code, release_tag, prefixes, updated_at) VALUES (?, ?, ?, ?)",
			List.of(code.toUpperCase(Locale.ROOT), release == null ? "" : release, prefixes == null ? "" : prefixes, System.currentTimeMillis())
		);
		reload();
	}

	/** Forget a country no rule names any more. */
	public synchronized void removeCountry(String code) {
		database.update("DELETE FROM luna_firewall_countries WHERE code = ?", List.of(code.toUpperCase(Locale.ROOT)));
		reload();
	}

	// -- activity ------------------------------------------------------------------------

	/** Refusals per hour and reason in a window, oldest first, the unwritten ones included. */
	public List<Map<String, Object>> hourlyCounts(long from, long to) {
		return hourlyCounts(from, to, "");
	}

	/**
	 * Refusals per hour and reason in a window, for one address when {@code ip} is
	 * given. The hourly table keeps no addresses, so one address's hours are summed
	 * from its refusal rows, which cover the last {@code REFUSALS_KEEP_MS}.
	 */
	public List<Map<String, Object>> hourlyCounts(long from, long to, String ip) {
		Map<String, Long> merged = new LinkedHashMap<>();

		if (ip != null && !ip.isBlank()) {
			if (available()) {
				for (Map<String, Object> row : database.query(
					"SELECT at - MOD(at, ?) AS hour, reason, SUM(count) AS total FROM luna_firewall_refusals"
						+ " WHERE ip = ? AND at >= ? AND at < ? GROUP BY hour, reason",
					List.of(HOUR, ip, from - from % HOUR, to)
				)) {
					merged.merge(longOf(row.get("hour")) + "|" + row.get("reason"), longOf(row.get("total")), Long::sum);
				}
			}

			for (Refusal row : List.copyOf(pending.values())) {
				if (ip.equals(row.ip) && row.at >= from - from % HOUR && row.at < to) {
					merged.merge((row.at - row.at % HOUR) + "|" + row.reason, row.count.get(), Long::sum);
				}
			}

			return hourlyRows(merged);
		}

		if (available()) {
			for (Map<String, Object> row : database.query(
				"SELECT hour, reason, count FROM luna_firewall_hourly WHERE hour >= ? AND hour < ? ORDER BY hour",
				List.of(from - from % HOUR, to)
			)) {
				merged.merge(row.get("hour") + "|" + row.get("reason"), longOf(row.get("count")), Long::sum);
			}
		}

		for (Map.Entry<String, AtomicLong> entry : hourly.entrySet()) {
			long hour = Long.parseLong(entry.getKey().substring(0, entry.getKey().indexOf('|')));

			if (hour >= from - from % HOUR && hour < to) {
				merged.merge(entry.getKey(), entry.getValue().get(), Long::sum);
			}
		}

		return hourlyRows(merged);
	}

	private static List<Map<String, Object>> hourlyRows(Map<String, Long> merged) {
		List<Map<String, Object>> out = new ArrayList<>();

		for (Map.Entry<String, Long> entry : merged.entrySet()) {
			int bar = entry.getKey().indexOf('|');
			Map<String, Object> row = new LinkedHashMap<>();

			row.put("hour", Long.parseLong(entry.getKey().substring(0, bar)));
			row.put("reason", entry.getKey().substring(bar + 1));
			row.put("count", entry.getValue());
			out.add(row);
		}

		out.sort((left, right) -> Long.compare((long) left.get("hour"), (long) right.get("hour")));
		return out;
	}

	/** The most recent refusal rows in a window, newest first, optionally for one address. */
	public List<Map<String, Object>> recentRefusals(long from, long to, String ip, int limit) {
		flushSafely();

		if (!available()) {
			return List.of();
		}

		List<Map<String, Object>> rows = ip == null || ip.isBlank()
			? database.query(
				"SELECT at, ip, username, kind, reason, detail, count FROM luna_firewall_refusals WHERE at >= ? AND at < ? ORDER BY at DESC LIMIT ?",
				List.of(from, to, limit)
			)
			: database.query(
				"SELECT at, ip, username, kind, reason, detail, count FROM luna_firewall_refusals WHERE ip = ? AND at >= ? AND at < ? ORDER BY at DESC LIMIT ?",
				List.of(ip, from, to, limit)
			);
		List<Map<String, Object>> out = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			Map<String, Object> clean = new LinkedHashMap<>();

			clean.put("at", longOf(row.get("at")));
			clean.put("ip", String.valueOf(row.get("ip")));
			clean.put("username", String.valueOf(row.get("username")));
			clean.put("kind", String.valueOf(row.get("kind")));
			clean.put("reason", String.valueOf(row.get("reason")));
			clean.put("detail", String.valueOf(row.get("detail")));
			clean.put("count", longOf(row.get("count")));
			out.add(clean);
		}

		return out;
	}

	/** The addresses refused most in a window, with how often. */
	public List<Map<String, Object>> topRefused(long from, long to, int limit) {
		return topRefused(from, to, "", limit);
	}

	/** The addresses refused most in a window, or only {@code ip}'s row when one is given. */
	public List<Map<String, Object>> topRefused(long from, long to, String ip, int limit) {
		flushSafely();

		if (!available()) {
			return List.of();
		}

		List<Map<String, Object>> out = new ArrayList<>();
		boolean one = ip != null && !ip.isBlank();
		List<Map<String, Object>> rows = one
			? database.query(
				"SELECT ip, SUM(count) AS total, MAX(at) AS last_at, MAX(username) AS username FROM luna_firewall_refusals"
					+ " WHERE ip = ? AND at >= ? AND at < ? GROUP BY ip",
				List.of(ip, from, to)
			)
			: database.query(
				"SELECT ip, SUM(count) AS total, MAX(at) AS last_at, MAX(username) AS username FROM luna_firewall_refusals"
					+ " WHERE at >= ? AND at < ? GROUP BY ip ORDER BY total DESC LIMIT ?",
				List.of(from, to, limit)
			);

		for (Map<String, Object> row : rows) {
			Map<String, Object> clean = new LinkedHashMap<>();

			clean.put("ip", String.valueOf(row.get("ip")));
			clean.put("count", longOf(row.get("total")));
			clean.put("lastAt", longOf(row.get("last_at")));
			clean.put("username", row.get("username") == null ? "" : String.valueOf(row.get("username")));
			out.add(clean);
		}

		return out;
	}

	// -- internals ------------------------------------------------------------------------

	private FirewallRule firstMatch(Compiled state, IpRanges.Address address, boolean allows) {
		if (address == null) {
			return null;
		}

		for (FirewallRule rule : state.rules()) {
			if (rule.enabled() && rule.allows() == allows && matches(state, rule, address)) {
				return rule;
			}
		}

		return null;
	}

	/** Every matcher a rule gives must hold: its addresses AND one of its countries. */
	private boolean matches(Compiled state, FirewallRule rule, IpRanges.Address address) {
		if (!rule.matchesSomething()) {
			return false;
		}

		if (!rule.addresses().isEmpty()) {
			IpRanges ranges = state.ruleAddresses().get(rule.id());

			if (ranges == null || !ranges.contains(address)) {
				return false;
			}
		}

		if (!rule.countries().isEmpty()) {
			boolean any = false;

			for (String code : rule.countries()) {
				IpRanges ranges = state.countries().get(code);

				if (ranges != null && ranges.contains(address)) {
					any = true;
					break;
				}
			}

			return any;
		}

		return true;
	}

	/**
	 * Ban an address a detector caught: never a protected one or one an allow rule
	 * covers, never twice at once, and for longer on each strike.
	 */
	private VelocityNetworkBanStore.Entry autoBan(String ip, String detector, String detail, long now) {
		Compiled state = compiled;
		IpRanges.Address address = IpRanges.parseAddress(ip);

		if (state.protect().contains(address) || firstMatch(state, address, true) != null) {
			return null;
		}

		if (banning.putIfAbsent(ip, Boolean.TRUE) != null) {
			return null;
		}

		try {
			VelocityNetworkBanStore.Entry existing = bans.activeBan(ip, now);

			if (existing != null) {
				return existing;
			}

			int strike = bans.nextStrike(ip);
			long until = now + state.settings().banLengthMillis(strike);
			String reason = reasonOf(detector) + (detail.isBlank() ? "" : " (" + detail + ")");

			moderation.banIp(ip, reason, "firewall", until, detector);
			trackers.remove(ip);
			logger.audit("Tự cấm " + ip + " do " + detector + " (lần " + strike + "): " + detail);
			return bans.activeBan(ip, now);
		} finally {
			banning.remove(ip);
		}
	}

	private static String reasonOf(String detector) {
		return switch (detector) {
			case "loginFlood" -> "Kết nối dồn dập";
			case "pingScan" -> "Quét danh sách máy chủ";
			case "suspiciousNames" -> "Tên đăng nhập đáng ngờ";
			case "failedLogins" -> "Sai mật khẩu nhiều lần";
			default -> "Tường lửa tự cấm";
		};
	}

	private static Component deniedMessage() {
		return Component.text("Kết nối từ địa chỉ của bạn không được phép vào mạng máy chủ này.", NamedTextColor.RED);
	}

	private void hit(FirewallRule rule) {
		ruleHits.computeIfAbsent(rule.id(), key -> new AtomicLong()).incrementAndGet();
	}

	/** Count a refusal: the hour's tally, and one row per address, kind and reason a minute. */
	private void refuse(long now, String ip, String kind, String reason, String username, String detail) {
		long hour = now - now % HOUR;

		hourly.computeIfAbsent(hour + "|" + reason, key -> new AtomicLong()).incrementAndGet();

		long minute = now - now % MINUTE;
		Refusal row = pending.computeIfAbsent(
			minute + "|" + ip + "|" + kind + "|" + reason,
			key -> new Refusal(now, ip, kind, reason, username, detail)
		);

		row.count.incrementAndGet();

		if (!username.isBlank()) {
			row.username = username;
		}
	}

	private Tracker tracker(String ip, long now) {
		if (trackers.size() >= MAX_TRACKED && !trackers.containsKey(ip)) {
			evictQuietest(now);
		}

		Tracker tracker = trackers.computeIfAbsent(ip, key -> new Tracker());

		tracker.lastSeenAt = now;
		return tracker;
	}

	/** Drop the addresses not seen for the longest, a tenth of the table at a time. */
	private void evictQuietest(long now) {
		long cutoff = now - 10 * MINUTE;
		Iterator<Map.Entry<String, Tracker>> iterator = trackers.entrySet().iterator();

		while (iterator.hasNext()) {
			if (iterator.next().getValue().lastSeenAt < cutoff) {
				iterator.remove();
			}
		}

		if (trackers.size() < MAX_TRACKED) {
			return;
		}

		int drop = MAX_TRACKED / 10;
		Iterator<String> keys = trackers.keySet().iterator();

		while (keys.hasNext() && drop-- > 0) {
			keys.next();
			keys.remove();
		}
	}

	private static void trim(ArrayDeque<Long> times, long before) {
		while (!times.isEmpty() && times.peekFirst() < before) {
			times.pollFirst();
		}
	}

	private void flushSafely() {
		try {
			flush();
		} catch (RuntimeException exception) {
			logger.error("Ghi số liệu tường lửa thất bại: " + exception.getMessage(), exception);
		}
	}

	/** Write the minute's counters: rule hits, hourly tallies and refusal rows. */
	private synchronized void flush() {
		if (!available()) {
			return;
		}

		long now = System.currentTimeMillis();

		for (Map.Entry<String, AtomicLong> entry : List.copyOf(ruleHits.entrySet())) {
			long count = entry.getValue().getAndSet(0);

			if (count > 0) {
				database.update(
					"UPDATE luna_firewall_rules SET hits = hits + ?, last_hit_at = ? WHERE id = ?",
					List.of(count, now, entry.getKey())
				);
			}
		}

		for (Map.Entry<String, AtomicLong> entry : List.copyOf(hourly.entrySet())) {
			long count = entry.getValue().getAndSet(0);

			if (count == 0) {
				hourly.remove(entry.getKey(), entry.getValue());
				continue;
			}

			int bar = entry.getKey().indexOf('|');

			database.update(
				"INSERT INTO luna_firewall_hourly (hour, reason, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = count + VALUES(count)",
				List.of(Long.parseLong(entry.getKey().substring(0, bar)), entry.getKey().substring(bar + 1), count)
			);
		}

		long currentMinute = now - now % MINUTE;

		for (Map.Entry<String, Refusal> entry : List.copyOf(pending.entrySet())) {
			Refusal row = entry.getValue();

			// the minute still open keeps gathering; it is written once it closes
			if (row.at - row.at % MINUTE >= currentMinute) {
				continue;
			}

			pending.remove(entry.getKey());
			database.update(
				"INSERT INTO luna_firewall_refusals (at, ip, username, kind, reason, detail, count) VALUES (?, ?, ?, ?, ?, ?, ?)",
				List.of(row.at, row.ip, truncate(row.username, 32), row.kind, row.reason, truncate(row.detail, 255), row.count.get())
			);
		}

		if (!ruleHits.isEmpty()) {
			reload();
		}
	}

	private void pruneSafely() {
		if (!available()) {
			return;
		}

		try {
			long now = System.currentTimeMillis();

			database.update("DELETE FROM luna_firewall_refusals WHERE at < ?", List.of(now - REFUSALS_KEEP_MS));
			database.update("DELETE FROM luna_firewall_hourly WHERE hour < ?", List.of(now - HOURLY_KEEP_MS));
		} catch (RuntimeException exception) {
			logger.error("Dọn số liệu tường lửa thất bại: " + exception.getMessage(), exception);
		}
	}

	private String newId() {
		byte[] bytes = new byte[5];

		random.nextBytes(bytes);
		return "fw_" + HexFormat.of().formatHex(bytes);
	}

	private static String addressOf(InetSocketAddress remote) {
		if (remote == null || remote.getAddress() == null) {
			return null;
		}

		return IpRanges.normalize(remote.getAddress().getHostAddress());
	}

	private static FirewallRule ruleOf(Map<String, Object> row) {
		return new FirewallRule(
			String.valueOf(row.get("id")),
			String.valueOf(row.get("action")),
			longOf(row.get("enabled")) != 0,
			split(row.get("addresses"), "\n"),
			split(row.get("countries"), ","),
			row.get("note") == null ? "" : String.valueOf(row.get("note")),
			row.get("created_by") == null ? "" : String.valueOf(row.get("created_by")),
			longOf(row.get("created_at")),
			longOf(row.get("updated_at")),
			longOf(row.get("hits")),
			longOf(row.get("last_hit_at"))
		);
	}

	private static List<String> split(Object value, String separator) {
		if (value == null) {
			return List.of();
		}

		List<String> out = new ArrayList<>();

		for (String part : String.valueOf(value).split(separator)) {
			String trimmed = part.trim();

			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}

		return out;
	}

	private static String truncate(String text, int max) {
		String value = text == null ? "" : text;

		return value.length() <= max ? value : value.substring(0, max);
	}

	private static long longOf(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value == null) {
			return 0L;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}
}
