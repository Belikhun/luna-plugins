package dev.belikhun.luna.legacy.serverselector;

import java.util.Collections;

import dev.belikhun.luna.legacy.string.Strings;

import dev.belikhun.luna.legacy.heartbeat.BackendHeartbeatStats;
import dev.belikhun.luna.legacy.heartbeat.BackendServerStatus;
import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.string.Formatters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerSelectorEngine {
	private static final int PAGE_SIZE = 45;
	private static final Pattern MC_VERSION_PATTERN = Pattern.compile("\\(MC:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEMVER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");
	private static final Pattern CONDITION_COMPARISON_PATTERN = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");

	private ServerSelectorEngine() {
	}

	public static ServerSelectorPayload parsePayload(byte[] rawPayload) {
		if (rawPayload == null || rawPayload.length == 0) {
			return ServerSelectorPayload.empty();
		}
		return parsePayload(PluginMessageReader.of(rawPayload));
	}

	public static ServerSelectorPayload parsePayload(PluginMessageReader reader) {
		try {
			String mode = reader.readUtf();
			boolean v3 = "open-v3".equalsIgnoreCase(mode);
			boolean v4 = "open-v4".equalsIgnoreCase(mode);
			boolean v5 = "open-v5".equalsIgnoreCase(mode);
			boolean v6 = "open-v6".equalsIgnoreCase(mode);
			boolean v7 = "open-v7".equalsIgnoreCase(mode);
			boolean v8 = "open-v8".equalsIgnoreCase(mode);
			if (!v3 && !v4 && !v5 && !v6 && !v7 && !v8) {
				return ServerSelectorPayload.empty();
			}

			String guiTitle = reader.readUtf();
			String name = reader.readUtf();
			List<String> header = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
			String bodyLine = reader.readUtf();
			List<String> footer = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
			String templateMaterial = (v7 || v8) ? reader.readUtf() : "";
			Map<String, TemplateOverridePayload> globalTemplateByStatus = readTemplateOverrides(reader);
			TemplatePayload baseTemplate = new TemplatePayload(name, header, bodyLine, footer, templateMaterial, globalTemplateByStatus);

			Map<String, String> statusColors = defaultStatusColors();
			Map<String, String> statusIcons = defaultStatusIcons();
			if (v4 || v5 || v6 || v7 || v8) {
				Map<String, String> payloadColors = new LinkedHashMap<>();
				Map<String, String> payloadIcons = new LinkedHashMap<>();
				int count = Math.max(0, reader.readInt());
				for (int i = 0; i < count; i++) {
					String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
					payloadColors.put(statusKey, reader.readUtf());
					payloadIcons.put(statusKey, reader.readUtf());
				}
				if (!payloadColors.isEmpty()) {
					statusColors = Collections.unmodifiableMap(new LinkedHashMap<>(payloadColors));
				}
				if (!payloadIcons.isEmpty()) {
					statusIcons = Collections.unmodifiableMap(new LinkedHashMap<>(payloadIcons));
				}
			}

			int serverCount = Math.max(0, reader.readInt());
			Map<String, ServerPayload> servers = new LinkedHashMap<>();
			for (int i = 0; i < serverCount; i++) {
				String backendName = reader.readUtf();
				String display = reader.readUtf();
				String accent = reader.readUtf();
				String permission = reader.readUtf();
				String hostName = v8 ? reader.readUtf() : backendName;
				int rawSlot = reader.readInt();
				int rawPage = reader.readInt();
				Integer slot = rawSlot < 0 ? null : rawSlot;
				Integer page = rawPage < 0 ? null : rawPage;

				String material = "";
				Map<String, String> materialByStatus = Collections.emptyMap();
				Boolean glint = null;
				Map<String, Boolean> glintByStatus = Collections.emptyMap();
				if (v5 || v6 || v7 || v8) {
					material = reader.readUtf();
					int materialByStatusCount = Math.max(0, reader.readInt());
					Map<String, String> materialOverrides = new LinkedHashMap<>();
					for (int index = 0; index < materialByStatusCount; index++) {
						String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
						materialOverrides.put(statusKey, reader.readUtf());
					}
					materialByStatus = Collections.unmodifiableMap(new LinkedHashMap<>(materialOverrides));

					if (reader.readBoolean()) {
						glint = reader.readBoolean();
					}

					int glintByStatusCount = Math.max(0, reader.readInt());
					Map<String, Boolean> glintOverrides = new LinkedHashMap<>();
					for (int index = 0; index < glintByStatusCount; index++) {
						String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
						glintOverrides.put(statusKey, reader.readBoolean());
					}
					glintByStatus = Collections.unmodifiableMap(new LinkedHashMap<>(glintOverrides));
				}

				List<ConditionalOverridePayload> conditional = Collections.emptyList();
				if (v6 || v7 || v8) {
					int conditionalCount = Math.max(0, reader.readInt());
					List<ConditionalOverridePayload> conditionalPayload = new ArrayList<>();
					for (int index = 0; index < conditionalCount; index++) {
						String when = reader.readUtf();
						String conditionalMaterial = null;
						if (reader.readBoolean()) {
							conditionalMaterial = reader.readUtf();
						}

						Boolean conditionalGlint = null;
						if (reader.readBoolean()) {
							conditionalGlint = reader.readBoolean();
						}

						List<String> conditionalDescription = null;
						if (reader.readBoolean()) {
							conditionalDescription = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
						}

						TemplateOverridePayload templateOverride = null;
						if (reader.readBoolean()) {
							templateOverride = readTemplateOverride(reader);
						}

						conditionalPayload.add(new ConditionalOverridePayload(
							when,
							conditionalMaterial,
							conditionalGlint,
							conditionalDescription,
							templateOverride
						));
					}
					conditional = Collections.unmodifiableList(new ArrayList<>(conditionalPayload));
				}

				List<String> description = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
				int statusDescriptionCount = Math.max(0, reader.readInt());
				Map<String, List<String>> descriptionByStatus = new LinkedHashMap<>();
				for (int index = 0; index < statusDescriptionCount; index++) {
					String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
					descriptionByStatus.put(statusKey, Collections.unmodifiableList(new ArrayList<>(readLines(reader))));
				}

				TemplatePayload serverTemplate = null;
				if (reader.readBoolean()) {
					String serverTemplateName = reader.readUtf();
					List<String> serverTemplateHeader = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
					String serverTemplateBody = reader.readUtf();
					List<String> serverTemplateFooter = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
					String serverTemplateMaterial = "";
					if (v7 || v8) {
						serverTemplateMaterial = reader.readUtf();
					}
					serverTemplate = new TemplatePayload(
						serverTemplateName,
						serverTemplateHeader,
						serverTemplateBody,
						serverTemplateFooter,
						serverTemplateMaterial,
						readTemplateOverrides(reader)
					);
				}

				servers.put(normalize(backendName), new ServerPayload(
					backendName,
					display,
					accent,
					permission,
					hostName,
					slot,
					page,
					material,
					materialByStatus,
					glint,
					glintByStatus,
					conditional,
					description,
					Collections.unmodifiableMap(new LinkedHashMap<>(descriptionByStatus)),
					serverTemplate
				));
			}
			return new ServerSelectorPayload(guiTitle, baseTemplate, statusColors, statusIcons, Collections.unmodifiableMap(new LinkedHashMap<>(servers)));
		} catch (Exception ignored) {
			return ServerSelectorPayload.empty();
		}
	}

	public static Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(
		ServerSelectorPayload payload,
		Map<String, BackendServerStatus> snapshot,
		Consumer<String> debugLogger
	) {
		List<ServerRenderEntry> entries = sortedEntries(payload, snapshot == null ? Collections.emptyMap() : snapshot, debugLogger);
		Map<Integer, Map<Integer, ServerRenderEntry>> byPage = new LinkedHashMap<>();
		List<ServerRenderEntry> unresolved = new ArrayList<>();

		for (ServerRenderEntry entry : entries) {
			ServerPayload payloadEntry = entry.payload();
			if (payloadEntry == null || payloadEntry.slot() == null || payloadEntry.page() == null) {
				unresolved.add(entry);
				continue;
			}

			int slot = payloadEntry.slot();
			int page = payloadEntry.page() - 1;
			if (slot < 0 || slot >= PAGE_SIZE || page < 0) {
				unresolved.add(entry);
				continue;
			}

			byPage.computeIfAbsent(page, ignored -> new LinkedHashMap<>()).put(slot, entry);
		}

		int pagePointer = 0;
		for (ServerRenderEntry entry : unresolved) {
			while (true) {
				Map<Integer, ServerRenderEntry> pageLayout = byPage.computeIfAbsent(pagePointer, ignored -> new LinkedHashMap<>());
				int slot = firstFreeSlot(pageLayout);
				if (slot == -1) {
					pagePointer++;
					continue;
				}
				pageLayout.put(slot, entry);
				break;
			}
		}

		return byPage;
	}

	public static RenderedServerItem renderServerItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		ServerSelectorPayload payload,
		boolean noPermission
	) {
		String statusText = resolveStatus(status, noPermission);
		String statusColor = payload == null ? "<white>" : payload.statusColor(statusText);
		String statusIcon = payload == null ? "●" : payload.statusIcon(statusText);

		BackendHeartbeatStats stats = status == null ? null : status.stats();
		int onlinePlayers = stats == null ? 0 : stats.onlinePlayers();
		int maxPlayers = stats == null ? 0 : stats.maxPlayers();
		String display = serverPayload != null && !Strings.isBlank(safe(serverPayload.displayName()))
			? serverPayload.displayName()
			: (Strings.isBlank(safe(status == null ? "" : status.serverDisplay())) ? safe(status == null ? "" : status.serverName()) : status.serverDisplay());

		ConditionContext conditionContext = new ConditionContext(
			statusText,
			safe(status == null ? "" : status.serverName()),
			serverPayload != null && !Strings.isBlank(safe(serverPayload.hostName())) ? serverPayload.hostName() : safe(status == null ? "" : status.serverName()),
			display,
			onlinePlayers,
			maxPlayers,
			stats != null && stats.whitelistEnabled(),
			noPermission,
			stats == null ? 0D : stats.tps(),
			stats == null ? 0D : stats.systemCpuUsagePercent(),
			stats == null ? 0L : stats.heartbeatLatencyMillis(),
			stats == null || stats.ramMaxBytes() <= 0L
				? 0D
				: Math.min(100D, (Math.max(0L, stats.ramUsedBytes()) * 100D) / Math.max(1L, stats.ramMaxBytes()))
		);

		ConditionalOverridePayload conditionalOverride = serverPayload == null ? null : serverPayload.resolveConditional(conditionContext);
		TemplatePayload template = payload == null ? TemplatePayload.defaultTemplate() : payload.resolveTemplate(serverPayload, statusText);
		if (conditionalOverride != null && conditionalOverride.templateOverride() != null) {
			template = template.applyOverride(conditionalOverride.templateOverride());
		}

		String materialName = resolveMaterialName(statusText, serverPayload, conditionalOverride, template);
		Boolean glint = resolveGlint(statusText, serverPayload, conditionalOverride);
		Map<String, String> values = placeholderValues(status, serverPayload, display, statusText, statusColor, statusIcon, onlinePlayers, maxPlayers);

		List<String> lore = new ArrayList<>();
		for (String headerLine : template.headerLines()) {
			lore.add(applyTemplate(safe(headerLine), values));
		}

		List<String> description = serverPayload == null ? Collections.emptyList() : serverPayload.description(statusText);
		if (conditionalOverride != null && conditionalOverride.description() != null) {
			description = conditionalOverride.description();
		}
		for (String line : description) {
			Map<String, String> withLine = new LinkedHashMap<>(values);
			withLine.put("line", safe(line));
			lore.add(applyTemplate(template.bodyLineTemplate(), withLine));
		}

		for (String footerLine : template.footerLines()) {
			lore.add(applyTemplate(safe(footerLine), values));
		}

		return new RenderedServerItem(applyTemplate(template.nameTemplate(), values), Collections.unmodifiableList(new ArrayList<>(lore)), materialName, glint, statusText);
	}

	public static DashboardStats dashboardStats(Map<String, BackendServerStatus> snapshot) {
		Map<String, BackendServerStatus> safeSnapshot = snapshot == null ? Collections.emptyMap() : snapshot;
		List<BackendServerStatus> all = new ArrayList<>(safeSnapshot.values());
		all.sort(Comparator.comparing(status -> safe(status.serverName()).toLowerCase(Locale.ROOT)));
		List<BackendServerStatus> onlineOnly = all.stream().filter(BackendServerStatus::online).collect(java.util.stream.Collectors.toList());

		double avgTps = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().tps());
		double avgCpu = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().systemCpuUsagePercent());
		double avgLatency = average(onlineOnly, status -> status.stats() == null ? 0D : status.stats().heartbeatLatencyMillis());
		long totalRamUsed = sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().ramUsedBytes());
		long totalRamMax = sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().ramMaxBytes());
		long maxUptimeMillis = maxLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().uptimeMillis());
		long totalPlayers = sumLong(onlineOnly, status -> status.stats() == null ? 0L : status.stats().onlinePlayers());
		int onlineServers = (int) all.stream().filter(BackendServerStatus::online).count();

		return new DashboardStats(Collections.unmodifiableList(new ArrayList<>(all)), Collections.unmodifiableList(new ArrayList<>(onlineOnly)), avgTps, avgCpu, avgLatency, totalRamUsed, totalRamMax, maxUptimeMillis, onlineServers, totalPlayers);
	}

	public static String resolveStatus(BackendServerStatus status, boolean noPermission) {
		return SelectorStatusResolver.resolve(status, noPermission);
	}

	public static String applyTemplate(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}

	private static List<ServerRenderEntry> sortedEntries(
		ServerSelectorPayload payload,
		Map<String, BackendServerStatus> snapshot,
		Consumer<String> debugLogger
	) {
		List<ServerRenderEntry> entries = new ArrayList<>();
		if (payload != null && !payload.servers().isEmpty()) {
			for (ServerPayload item : payload.servers().values()) {
				BackendServerStatus status = resolveServerStatus(item, snapshot, debugLogger);
				entries.add(new ServerRenderEntry(status, item));
			}
		} else {
			for (BackendServerStatus status : snapshot.values()) {
				entries.add(new ServerRenderEntry(status, null));
			}
		}
		entries.sort(Comparator.comparing(entry -> safe(entry.status().serverName()).toLowerCase(Locale.ROOT)));
		return entries;
	}

	private static BackendServerStatus resolveServerStatus(
		ServerPayload payload,
		Map<String, BackendServerStatus> snapshot,
		Consumer<String> debugLogger
	) {
		String backendName = payload.backendName();
		if (!Strings.isBlank(safe(backendName))) {
			BackendServerStatus direct = snapshot.get(normalize(backendName));
			if (direct != null) {
				return direct;
			}

			for (BackendServerStatus candidate : snapshot.values()) {
				if (candidate != null && safe(candidate.serverName()).equalsIgnoreCase(backendName.trim())) {
					debug(debugLogger, "status fallback by serverName match: config=" + backendName + " -> heartbeat=" + candidate.serverName());
					return candidate;
				}
			}
		}

		String displayName = payload.displayName();
		if (!Strings.isBlank(safe(displayName))) {
			BackendServerStatus matchedByDisplay = null;
			for (BackendServerStatus candidate : snapshot.values()) {
				if (candidate == null || !safe(candidate.serverDisplay()).equalsIgnoreCase(displayName.trim())) {
					continue;
				}
				if (matchedByDisplay != null) {
					matchedByDisplay = null;
					break;
				}
				matchedByDisplay = candidate;
			}
			if (matchedByDisplay != null) {
				debug(debugLogger, "status fallback by display match: config=" + backendName + ", display=" + displayName + " -> heartbeat=" + matchedByDisplay.serverName());
				return matchedByDisplay;
			}
		}

		debug(debugLogger, "status unresolved, fallback offline: config=" + backendName + ", display=" + displayName + ", snapshotSize=" + snapshot.size());
		return new BackendServerStatus(payload.backendName(), payload.displayName(), payload.accentColor(), false, 0L, null);
	}

	private static String resolveMaterialName(
		String statusText,
		ServerPayload serverPayload,
		ConditionalOverridePayload conditionalOverride,
		TemplatePayload template
	) {
		if (conditionalOverride != null && !Strings.isBlank(safe(conditionalOverride.material()))) {
			return conditionalOverride.material();
		}
		if (serverPayload != null && !Strings.isBlank(safe(serverPayload.material(statusText)))) {
			return serverPayload.material(statusText);
		}
		if (template != null && !Strings.isBlank(safe(template.material()))) {
			return template.material();
		}
		if ("NOP".equals(statusText)) {
			return "gray_concrete";
		}
		if ("OFFLINE".equals(statusText)) {
			return "red_concrete";
		}
		if ("MAINT".equals(statusText)) {
			return "yellow_concrete";
		}
		return "lime_concrete";
	}

	private static Boolean resolveGlint(String statusText, ServerPayload serverPayload, ConditionalOverridePayload conditionalOverride) {
		if (conditionalOverride != null && conditionalOverride.glint() != null) {
			return conditionalOverride.glint();
		}
		return serverPayload == null ? null : serverPayload.glint(statusText);
	}

	private static Map<String, String> placeholderValues(
		BackendServerStatus status,
		ServerPayload serverPayload,
		String display,
		String statusText,
		String statusColor,
		String statusIcon,
		int online,
		int max
	) {
		Map<String, String> values = new LinkedHashMap<>();
		String serverName = safe(status == null ? "" : status.serverName());
		String hostName = serverPayload != null && !Strings.isBlank(safe(serverPayload.hostName())) ? serverPayload.hostName() : serverName;
		BackendHeartbeatStats stats = status == null ? null : status.stats();

		values.put("server_name", serverName);
		values.put("luna_host_name", hostName);
		values.put("luna_server_name", hostName);
		values.put("server_display", safe(display));
		values.put("server_accent_color", safe(status == null ? "" : status.serverAccentColor()));
		values.put("server_status", statusText);
		values.put("server_status_color", statusColor);
		values.put("server_status_icon", statusIcon);
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));

		long ramUsedBytes = stats == null ? 0L : Math.max(0L, stats.ramUsedBytes());
		long ramMaxBytes = stats == null ? 0L : Math.max(0L, stats.ramMaxBytes());
		String versionFull = stats == null ? "unknown" : safe(stats.version());
		String versionShort = shortVersion(versionFull);
		String software = stats == null ? "unknown" : safe(stats.software());
		String motd = stats == null ? "" : safe(stats.motd());

		values.put("version", versionShort);
		values.put("server_version", versionShort);
		values.put("server_version_full", versionFull);
		values.put("software", software);
		values.put("server_software", software);
		values.put("motd", motd);
		values.put("tps", stats == null ? "0.00" : String.format(Locale.US, "%.2f", stats.tps()));

		long uptimeMillis = stats == null ? 0L : Math.max(0L, stats.uptimeMillis());
		values.put("uptime", Formatters.compactDuration(Duration.ofMillis(uptimeMillis)));
		values.put("uptime_long", Formatters.duration(Duration.ofMillis(uptimeMillis)));
		values.put("cpu_usage", stats == null ? "0.0" : String.format(Locale.US, "%.1f", Math.max(0D, stats.systemCpuUsagePercent())));
		values.put("ram_used_mb", String.valueOf(ramUsedBytes / 1024L / 1024L));
		values.put("ram_max_mb", String.valueOf(ramMaxBytes / 1024L / 1024L));
		values.put("ram_percent", String.format(Locale.US, "%.1f", ramMaxBytes <= 0L ? 0D : Math.min(100D, (ramUsedBytes * 100D) / ramMaxBytes)));
		values.put("latency_ms", String.valueOf(stats == null ? 0L : Math.max(0L, stats.heartbeatLatencyMillis())));
		return values;
	}

	private static boolean evaluateConditionExpression(String expression, ConditionContext context) {
		if (Strings.isBlank(expression)) {
			return false;
		}
		for (String orClause : splitConditionExpression(expression, "||")) {
			boolean andResult = true;
			for (String andClause : splitConditionExpression(orClause, "&&")) {
				if (!evaluateConditionPredicate(andClause, context)) {
					andResult = false;
					break;
				}
			}
			if (andResult) {
				return true;
			}
		}
		return false;
	}

	private static List<String> splitConditionExpression(String expression, String operator) {
		List<String> output = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = '\0';
		for (int index = 0; index < expression.length(); index++) {
			char ch = expression.charAt(index);
			if (quote == '\0' && (ch == '\'' || ch == '"')) {
				quote = ch;
				current.append(ch);
				continue;
			}
			if (quote != '\0') {
				current.append(ch);
				if (ch == quote) {
					quote = '\0';
				}
				continue;
			}
			if (index + operator.length() <= expression.length() && expression.substring(index, index + operator.length()).equals(operator)) {
				output.add(current.toString().trim());
				current.setLength(0);
				index += operator.length() - 1;
				continue;
			}
			current.append(ch);
		}
		output.add(current.toString().trim());
		return output;
	}

	private static boolean evaluateConditionPredicate(String rawPredicate, ConditionContext context) {
		String predicate = trimBalancedParentheses(rawPredicate == null ? "" : rawPredicate.trim());
		if (predicate.isEmpty()) {
			return false;
		}
		if (predicate.startsWith("!")) {
			return !evaluateConditionPredicate(predicate.substring(1), context);
		}
		if ("true".equalsIgnoreCase(predicate)) {
			return true;
		}
		if ("false".equalsIgnoreCase(predicate)) {
			return false;
		}

		Matcher matcher = CONDITION_COMPARISON_PATTERN.matcher(predicate);
		if (!matcher.matches()) {
			Object value = resolveConditionVariable(predicate, context);
			return truthy(value);
		}

		Object leftValue = resolveConditionVariable(matcher.group(1), context);
		Object rightValue = parseConditionRightLiteral(matcher.group(3), context);
		return compareConditionValues(leftValue, matcher.group(2), rightValue);
	}

	private static String trimBalancedParentheses(String value) {
		String output = value;
		while (output.startsWith("(") && output.endsWith(")") && isWrappedBySingleBalancedPair(output)) {
			output = output.substring(1, output.length() - 1).trim();
		}
		return output;
	}

	private static boolean isWrappedBySingleBalancedPair(String value) {
		int depth = 0;
		char quote = '\0';
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (quote == '\0' && (ch == '\'' || ch == '"')) {
				quote = ch;
				continue;
			}
			if (quote != '\0') {
				if (ch == quote) {
					quote = '\0';
				}
				continue;
			}
			if (ch == '(') {
				depth++;
			} else if (ch == ')') {
				depth--;
				if (depth == 0 && index < value.length() - 1) {
					return false;
				}
			}
		}
		return depth == 0;
	}

	private static Object parseConditionRightLiteral(String raw, ConditionContext context) {
		String text = trimBalancedParentheses(raw == null ? "" : raw.trim());
		if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
			return text.substring(1, text.length() - 1);
		}
		if ("true".equalsIgnoreCase(text)) {
			return true;
		}
		if ("false".equalsIgnoreCase(text)) {
			return false;
		}
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException ignored) {
			Object variable = resolveConditionVariable(text, context);
			return variable != null ? variable : text;
		}
	}

	private static boolean compareConditionValues(Object left, String operator, Object right) {
		Double leftNumber = toNumber(left);
		Double rightNumber = toNumber(right);
		if (leftNumber != null && rightNumber != null) {
			switch (operator) {
				case "==":
					return Double.compare(leftNumber, rightNumber) == 0;

				case "!=":
					return Double.compare(leftNumber, rightNumber) != 0;

				case ">":
					return leftNumber > rightNumber;

				case ">=":
					return leftNumber >= rightNumber;

				case "<":
					return leftNumber < rightNumber;

				case "<=":
					return leftNumber <= rightNumber;

				default:
					return false;
			}
		}
		if (left instanceof Boolean || right instanceof Boolean) {
			boolean leftBool = toBoolean(left);
			boolean rightBool = toBoolean(right);
			switch (operator) {
				case "==":
					return leftBool == rightBool;

				case "!=":
					return leftBool != rightBool;

				default:
					return false;
			}
		}

		String leftText = left == null ? "" : String.valueOf(left);
		String rightText = right == null ? "" : String.valueOf(right);
		int compare = leftText.compareToIgnoreCase(rightText);
		switch (operator) {
			case "==":
				return compare == 0;

			case "!=":
				return compare != 0;

			case ">":
				return compare > 0;

			case ">=":
				return compare >= 0;

			case "<":
				return compare < 0;

			case "<=":
				return compare <= 0;

			default:
				return false;
		}
	}

	private static boolean toBoolean(Object value) {
		if (value instanceof Boolean) {
			Boolean bool = (Boolean) value;

			return bool;
		}
		return truthy(value);
	}

	private static boolean truthy(Object value) {
		if (value instanceof Boolean) {
			Boolean bool = (Boolean) value;

			return bool;
		}
		Double number = toNumber(value);
		if (number != null) {
			return Math.abs(number) > 0.0000001D;
		}
		if (value == null) {
			return false;
		}
		String text = String.valueOf(value).trim();
		return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"no".equalsIgnoreCase(text) && !"0".equals(text);
	}

	private static Double toNumber(Object value) {
		if (value instanceof Number) {
			Number number = (Number) value;

			return number.doubleValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Double.parseDouble(String.valueOf(value).trim());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Object resolveConditionVariable(String variable, ConditionContext context) {
		if (Strings.isBlank(variable)) {
			return null;
		}
		String key = variable.trim().toLowerCase(Locale.ROOT);
		switch (key) {
			case "status":
			case "server_status":
				return context.status();

			case "server_name":
				return context.serverName();

			case "luna_host_name":
			case "luna_server_name":
				return context.hostName();

			case "server_display":
				return context.serverDisplay();

			case "online":
				return context.onlinePlayers();

			case "max":
				return context.maxPlayers();

			case "whitelist":
			case "maint":
				return context.whitelistEnabled();

			case "no_permission":
			case "nop":
				return context.noPermission();

			case "has_permission":
				return !context.noPermission();

			case "tps":
				return context.tps();

			case "cpu_usage":
				return context.cpuUsage();

			case "latency_ms":
				return context.latencyMs();

			case "ram_percent":
				return context.ramPercent();

			case "is_online":
				return "ONLINE".equalsIgnoreCase(context.status());

			case "is_offline":
				return "OFFLINE".equalsIgnoreCase(context.status());

			case "is_maint":
				return "MAINT".equalsIgnoreCase(context.status());

			case "is_nop":
				return "NOP".equalsIgnoreCase(context.status());

			default:
				return null;
		}
	}

	private static int firstFreeSlot(Map<Integer, ServerRenderEntry> pageLayout) {
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			if (!pageLayout.containsKey(slot)) {
				return slot;
			}
		}
		return -1;
	}

	private static List<String> readLines(PluginMessageReader reader) {
		int lineCount = Math.max(0, reader.readInt());
		List<String> lines = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			lines.add(reader.readUtf());
		}
		return lines;
	}

	private static Map<String, TemplateOverridePayload> readTemplateOverrides(PluginMessageReader reader) {
		int overrideCount = Math.max(0, reader.readInt());
		Map<String, TemplateOverridePayload> overrides = new LinkedHashMap<>();
		for (int i = 0; i < overrideCount; i++) {
			String status = reader.readUtf().toUpperCase(Locale.ROOT);
			overrides.put(status, readTemplateOverride(reader));
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
	}

	private static TemplateOverridePayload readTemplateOverride(PluginMessageReader reader) {
		String name = null;
		List<String> header = null;
		String bodyLine = null;
		List<String> footer = null;
		if (reader.readBoolean()) {
			name = reader.readUtf();
		}
		if (reader.readBoolean()) {
			header = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
		}
		if (reader.readBoolean()) {
			bodyLine = reader.readUtf();
		}
		if (reader.readBoolean()) {
			footer = Collections.unmodifiableList(new ArrayList<>(readLines(reader)));
		}
		return new TemplateOverridePayload(name, header, bodyLine, footer);
	}

	private static Map<String, String> defaultStatusColors() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("ONLINE", "<green>");
		values.put("OFFLINE", "<red>");
		values.put("MAINT", "<yellow>");
		values.put("NOP", "<gray>");
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static Map<String, String> defaultStatusIcons() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("ONLINE", "✔");
		values.put("OFFLINE", "✘");
		values.put("MAINT", "⚠");
		values.put("NOP", "🔒");
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static double average(List<BackendServerStatus> values, java.util.function.ToDoubleFunction<BackendServerStatus> mapper) {
		if (values.isEmpty()) {
			return 0D;
		}
		double total = 0D;
		int count = 0;
		for (BackendServerStatus value : values) {
			total += mapper.applyAsDouble(value);
			count++;
		}
		return count == 0 ? 0D : total / count;
	}

	private static long sumLong(List<BackendServerStatus> values, java.util.function.ToLongFunction<BackendServerStatus> mapper) {
		long total = 0L;
		for (BackendServerStatus value : values) {
			total += mapper.applyAsLong(value);
		}
		return total;
	}

	private static long maxLong(List<BackendServerStatus> values, java.util.function.ToLongFunction<BackendServerStatus> mapper) {
		long max = 0L;
		for (BackendServerStatus value : values) {
			max = Math.max(max, mapper.applyAsLong(value));
		}
		return max;
	}

	private static String shortVersion(String full) {
		if (Strings.isBlank(full)) {
			return "unknown";
		}
		Matcher mcMatcher = MC_VERSION_PATTERN.matcher(full);
		if (mcMatcher.find()) {
			return mcMatcher.group(1).trim();
		}
		Matcher semverMatcher = SEMVER_PATTERN.matcher(full);
		if (semverMatcher.find()) {
			return semverMatcher.group(1).trim();
		}
		return full.trim();
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static void debug(Consumer<String> debugLogger, String message) {
		if (debugLogger != null) {
			debugLogger.accept(message);
		}
	}

	public static final class DashboardStats {
		private final List<BackendServerStatus> allServers;
		private final List<BackendServerStatus> onlineServersOnly;
		private final double averageTps;
		private final double averageCpu;
		private final double averageLatency;
		private final long totalRamUsedBytes;
		private final long totalRamMaxBytes;
		private final long maxUptimeMillis;
		private final int onlineServerCount;
		private final long totalOnlinePlayers;

		public DashboardStats(List<BackendServerStatus> allServers, List<BackendServerStatus> onlineServersOnly, double averageTps, double averageCpu, double averageLatency, long totalRamUsedBytes, long totalRamMaxBytes, long maxUptimeMillis, int onlineServerCount, long totalOnlinePlayers) {
			this.allServers = allServers;
			this.onlineServersOnly = onlineServersOnly;
			this.averageTps = averageTps;
			this.averageCpu = averageCpu;
			this.averageLatency = averageLatency;
			this.totalRamUsedBytes = totalRamUsedBytes;
			this.totalRamMaxBytes = totalRamMaxBytes;
			this.maxUptimeMillis = maxUptimeMillis;
			this.onlineServerCount = onlineServerCount;
			this.totalOnlinePlayers = totalOnlinePlayers;
		}

		public List<BackendServerStatus> allServers() {
			return allServers;
		}

		public List<BackendServerStatus> onlineServersOnly() {
			return onlineServersOnly;
		}

		public double averageTps() {
			return averageTps;
		}

		public double averageCpu() {
			return averageCpu;
		}

		public double averageLatency() {
			return averageLatency;
		}

		public long totalRamUsedBytes() {
			return totalRamUsedBytes;
		}

		public long totalRamMaxBytes() {
			return totalRamMaxBytes;
		}

		public long maxUptimeMillis() {
			return maxUptimeMillis;
		}

		public int onlineServerCount() {
			return onlineServerCount;
		}

		public long totalOnlinePlayers() {
			return totalOnlinePlayers;
		}

		}

	public static final class RenderedServerItem {
		private final String title;
		private final List<String> lore;
		private final String materialName;
		private final Boolean glint;
		private final String status;

		public RenderedServerItem(String title, List<String> lore, String materialName, Boolean glint, String status) {
			this.title = title;
			this.lore = lore;
			this.materialName = materialName;
			this.glint = glint;
			this.status = status;
		}

		public String title() {
			return title;
		}

		public List<String> lore() {
			return lore;
		}

		public String materialName() {
			return materialName;
		}

		public Boolean glint() {
			return glint;
		}

		public String status() {
			return status;
		}

		}

	public static final class ServerRenderEntry {
		private final BackendServerStatus status;
		private final ServerPayload payload;

		public ServerRenderEntry(BackendServerStatus status, ServerPayload payload) {
			this.status = status;
			this.payload = payload;
		}

		public BackendServerStatus status() {
			return status;
		}

		public ServerPayload payload() {
			return payload;
		}

		}

	public static final class ServerSelectorPayload {
		private final String guiTitle;
		private final TemplatePayload template;
		private final Map<String, String> statusColors;
		private final Map<String, String> statusIcons;
		private final Map<String, ServerPayload> servers;

		public ServerSelectorPayload(String guiTitle, TemplatePayload template, Map<String, String> statusColors, Map<String, String> statusIcons, Map<String, ServerPayload> servers) {
			this.guiTitle = guiTitle;
			this.template = template;
			this.statusColors = statusColors;
			this.statusIcons = statusIcons;
			this.servers = servers;
		}

		public String guiTitle() {
			return guiTitle;
		}

		public TemplatePayload template() {
			return template;
		}

		public Map<String, String> statusColors() {
			return statusColors;
		}

		public Map<String, String> statusIcons() {
			return statusIcons;
		}

		public Map<String, ServerPayload> servers() {
			return servers;
		}

		public static ServerSelectorPayload empty() {
			return new ServerSelectorPayload("Danh Sách Máy Chủ", TemplatePayload.defaultTemplate(), defaultStatusColors(), defaultStatusIcons(), Collections.emptyMap());
		}

		public boolean isEmpty() {
			return servers.isEmpty();
		}

		public Optional<ServerPayload> server(String backendName) {
			if (Strings.isBlank(backendName)) {
				return Optional.empty();
			}
			return Optional.ofNullable(servers.get(normalize(backendName)));
		}

		public TemplatePayload resolveTemplate(ServerPayload serverPayload, String status) {
			TemplatePayload resolved = template.applyOverride(template.byStatus().get(status == null ? "" : status.toUpperCase(Locale.ROOT)));
			if (serverPayload == null || serverPayload.template() == null) {
				return resolved;
			}
			resolved = resolved.merge(serverPayload.template());
			return resolved.applyOverride(serverPayload.template().byStatus().get(status == null ? "" : status.toUpperCase(Locale.ROOT)));
		}

		public String statusColor(String status) {
			if (status == null) {
				return "<white>";
			}
			return statusColors.getOrDefault(status.toUpperCase(Locale.ROOT), "<white>");
		}

		public String statusIcon(String status) {
			if (status == null) {
				return "●";
			}
			return statusIcons.getOrDefault(status.toUpperCase(Locale.ROOT), "●");
		}
		}

	public static final class TemplatePayload {
		private final String nameTemplate;
		private final List<String> headerLines;
		private final String bodyLineTemplate;
		private final List<String> footerLines;
		private final String material;
		private final Map<String, TemplateOverridePayload> byStatus;

		public TemplatePayload(String nameTemplate, List<String> headerLines, String bodyLineTemplate, List<String> footerLines, String material, Map<String, TemplateOverridePayload> byStatus) {
			this.nameTemplate = nameTemplate;
			this.headerLines = headerLines;
			this.bodyLineTemplate = bodyLineTemplate;
			this.footerLines = footerLines;
			this.material = material;
			this.byStatus = byStatus;
		}

		public String nameTemplate() {
			return nameTemplate;
		}

		public List<String> headerLines() {
			return headerLines;
		}

		public String bodyLineTemplate() {
			return bodyLineTemplate;
		}

		public List<String> footerLines() {
			return footerLines;
		}

		public String material() {
			return material;
		}

		public Map<String, TemplateOverridePayload> byStatus() {
			return byStatus;
		}

		public static TemplatePayload defaultTemplate() {
			return new TemplatePayload("<b>%server_display%</b>", Collections.emptyList(), "%line%", Collections.emptyList(), "", Collections.emptyMap());
		}

		public TemplatePayload applyOverride(TemplateOverridePayload override) {
			if (override == null) {
				return this;
			}
			return new TemplatePayload(
				override.nameTemplate() != null ? override.nameTemplate() : nameTemplate,
				override.headerLines() != null ? override.headerLines() : headerLines,
				override.bodyLineTemplate() != null ? override.bodyLineTemplate() : bodyLineTemplate,
				override.footerLines() != null ? override.footerLines() : footerLines,
				material,
				byStatus
			);
		}

		public TemplatePayload merge(TemplatePayload serverTemplate) {
			return new TemplatePayload(
				serverTemplate.nameTemplate(),
				serverTemplate.headerLines(),
				serverTemplate.bodyLineTemplate(),
				serverTemplate.footerLines(),
				serverTemplate.material(),
				serverTemplate.byStatus()
			);
		}
		}

	public static final class TemplateOverridePayload {
		private final String nameTemplate;
		private final List<String> headerLines;
		private final String bodyLineTemplate;
		private final List<String> footerLines;

		public TemplateOverridePayload(String nameTemplate, List<String> headerLines, String bodyLineTemplate, List<String> footerLines) {
			this.nameTemplate = nameTemplate;
			this.headerLines = headerLines;
			this.bodyLineTemplate = bodyLineTemplate;
			this.footerLines = footerLines;
		}

		public String nameTemplate() {
			return nameTemplate;
		}

		public List<String> headerLines() {
			return headerLines;
		}

		public String bodyLineTemplate() {
			return bodyLineTemplate;
		}

		public List<String> footerLines() {
			return footerLines;
		}

		}

	public static final class ServerPayload {
		private final String backendName;
		private final String displayName;
		private final String accentColor;
		private final String permission;
		private final String hostName;
		private final Integer slot;
		private final Integer page;
		private final String material;
		private final Map<String, String> materialByStatus;
		private final Boolean glint;
		private final Map<String, Boolean> glintByStatus;
		private final List<ConditionalOverridePayload> conditional;
		private final List<String> description;
		private final Map<String, List<String>> descriptionByStatus;
		private final TemplatePayload template;

		public ServerPayload(String backendName, String displayName, String accentColor, String permission, String hostName, Integer slot, Integer page, String material, Map<String, String> materialByStatus, Boolean glint, Map<String, Boolean> glintByStatus, List<ConditionalOverridePayload> conditional, List<String> description, Map<String, List<String>> descriptionByStatus, TemplatePayload template) {
			this.backendName = backendName;
			this.displayName = displayName;
			this.accentColor = accentColor;
			this.permission = permission;
			this.hostName = hostName;
			this.slot = slot;
			this.page = page;
			this.material = material;
			this.materialByStatus = materialByStatus;
			this.glint = glint;
			this.glintByStatus = glintByStatus;
			this.conditional = conditional;
			this.description = description;
			this.descriptionByStatus = descriptionByStatus;
			this.template = template;
		}

		public String backendName() {
			return backendName;
		}

		public String displayName() {
			return displayName;
		}

		public String accentColor() {
			return accentColor;
		}

		public String permission() {
			return permission;
		}

		public String hostName() {
			return hostName;
		}

		public Integer slot() {
			return slot;
		}

		public Integer page() {
			return page;
		}

		public String material() {
			return material;
		}

		public Map<String, String> materialByStatus() {
			return materialByStatus;
		}

		public Boolean glint() {
			return glint;
		}

		public Map<String, Boolean> glintByStatus() {
			return glintByStatus;
		}

		public List<ConditionalOverridePayload> conditional() {
			return conditional;
		}

		public List<String> description() {
			return description;
		}

		public Map<String, List<String>> descriptionByStatus() {
			return descriptionByStatus;
		}

		public TemplatePayload template() {
			return template;
		}

		public String material(String status) {
			if (status != null) {
				String statusOverride = materialByStatus.get(status.toUpperCase(Locale.ROOT));
				if (statusOverride != null && !Strings.isBlank(statusOverride)) {
					return statusOverride;
				}
			}
			return material;
		}

		public Boolean glint(String status) {
			if (status != null && glintByStatus.containsKey(status.toUpperCase(Locale.ROOT))) {
				return glintByStatus.get(status.toUpperCase(Locale.ROOT));
			}
			return glint;
		}

		public ConditionalOverridePayload resolveConditional(ConditionContext context) {
			ConditionalOverridePayload merged = null;
			for (ConditionalOverridePayload override : conditional) {
				if (override == null || override.condition() == null || Strings.isBlank(override.condition())) {
					continue;
				}
				if (!evaluateConditionExpression(override.condition(), context)) {
					continue;
				}
				merged = merged == null ? override : merged.merge(override);
			}
			return merged;
		}

		public List<String> description(String status) {
			if (status == null) {
				return description;
			}
			List<String> override = descriptionByStatus.get(status.toUpperCase(Locale.ROOT));
			return override == null ? description : override;
		}
		}

	public static final class ConditionalOverridePayload {
		private final String condition;
		private final String material;
		private final Boolean glint;
		private final List<String> description;
		private final TemplateOverridePayload templateOverride;

		public ConditionalOverridePayload(String condition, String material, Boolean glint, List<String> description, TemplateOverridePayload templateOverride) {
			this.condition = condition;
			this.material = material;
			this.glint = glint;
			this.description = description;
			this.templateOverride = templateOverride;
		}

		public String condition() {
			return condition;
		}

		public String material() {
			return material;
		}

		public Boolean glint() {
			return glint;
		}

		public List<String> description() {
			return description;
		}

		public TemplateOverridePayload templateOverride() {
			return templateOverride;
		}

		public ConditionalOverridePayload merge(ConditionalOverridePayload other) {
			if (other == null) {
				return this;
			}
			return new ConditionalOverridePayload(
				other.condition(),
				other.material() != null ? other.material() : material,
				other.glint() != null ? other.glint() : glint,
				other.description() != null ? other.description() : description,
				other.templateOverride() != null ? mergeTemplateOverride(templateOverride, other.templateOverride()) : templateOverride
			);
		}

		private TemplateOverridePayload mergeTemplateOverride(TemplateOverridePayload base, TemplateOverridePayload override) {
			if (base == null) {
				return override;
			}
			if (override == null) {
				return base;
			}
			return new TemplateOverridePayload(
				override.nameTemplate() != null ? override.nameTemplate() : base.nameTemplate(),
				override.headerLines() != null ? override.headerLines() : base.headerLines(),
				override.bodyLineTemplate() != null ? override.bodyLineTemplate() : base.bodyLineTemplate(),
				override.footerLines() != null ? override.footerLines() : base.footerLines()
			);
		}
		}

	public static final class ConditionContext {
		private final String status;
		private final String serverName;
		private final String hostName;
		private final String serverDisplay;
		private final int onlinePlayers;
		private final int maxPlayers;
		private final boolean whitelistEnabled;
		private final boolean noPermission;
		private final double tps;
		private final double cpuUsage;
		private final long latencyMs;
		private final double ramPercent;

		public ConditionContext(String status, String serverName, String hostName, String serverDisplay, int onlinePlayers, int maxPlayers, boolean whitelistEnabled, boolean noPermission, double tps, double cpuUsage, long latencyMs, double ramPercent) {
			this.status = status;
			this.serverName = serverName;
			this.hostName = hostName;
			this.serverDisplay = serverDisplay;
			this.onlinePlayers = onlinePlayers;
			this.maxPlayers = maxPlayers;
			this.whitelistEnabled = whitelistEnabled;
			this.noPermission = noPermission;
			this.tps = tps;
			this.cpuUsage = cpuUsage;
			this.latencyMs = latencyMs;
			this.ramPercent = ramPercent;
		}

		public String status() {
			return status;
		}

		public String serverName() {
			return serverName;
		}

		public String hostName() {
			return hostName;
		}

		public String serverDisplay() {
			return serverDisplay;
		}

		public int onlinePlayers() {
			return onlinePlayers;
		}

		public int maxPlayers() {
			return maxPlayers;
		}

		public boolean whitelistEnabled() {
			return whitelistEnabled;
		}

		public boolean noPermission() {
			return noPermission;
		}

		public double tps() {
			return tps;
		}

		public double cpuUsage() {
			return cpuUsage;
		}

		public long latencyMs() {
			return latencyMs;
		}

		public double ramPercent() {
			return ramPercent;
		}

		}
}
