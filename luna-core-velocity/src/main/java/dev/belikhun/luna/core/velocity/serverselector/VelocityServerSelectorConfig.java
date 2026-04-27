package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.config.ConfigValues;
import dev.belikhun.luna.core.api.heartbeat.BackendMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record VelocityServerSelectorConfig(
	boolean enabled,
	boolean failOnServerOverrideFailure,
	SelectorDiagnostics diagnostics,
	String guiTitle,
	ServerTemplate template,
	String openingMessage,
	String playerOnlyMessage,
	String notFoundMessage,
	String offlineMessage,
	String maintMessage,
	String noPermissionMessage,
	String connectingMessage,
	Map<ServerSelectorStatus, String> statusColors,
	Map<ServerSelectorStatus, String> statusIcons,
	Map<String, ServerDefinition> servers,
	Map<String, ServerInfo> serverInfo
) {
	public static VelocityServerSelectorConfig from(Map<String, Object> rootConfig) {
		Map<String, Object> section = ConfigValues.map(rootConfig, "server-selector");
		Map<String, Object> templateSection = ConfigValues.map(section, "template");
		Map<String, Object> messages = ConfigValues.map(section, "messages");
		Map<String, Object> statusColorSection = ConfigValues.map(section, "status-colors");
		Map<String, Object> statusIconSection = ConfigValues.map(section, "status-icons");
		Map<String, Object> serversSection = ConfigValues.map(section, "servers");
		Map<String, Object> serverInfoSection = ConfigValues.map(rootConfig, "server-info");
		Map<String, Object> descriptionsSection = ConfigValues.map(section, "descriptions");
		Map<String, Object> diagnosticsSection = ConfigValues.map(section, "diagnostics");

		Map<ServerSelectorStatus, String> statusColors = new LinkedHashMap<>();
		statusColors.put(ServerSelectorStatus.ONLINE, ConfigValues.string(statusColorSection, "ONLINE", "<green>"));
		statusColors.put(ServerSelectorStatus.OFFLINE, ConfigValues.string(statusColorSection, "OFFLINE", "<red>"));
		statusColors.put(ServerSelectorStatus.MAINT, ConfigValues.string(statusColorSection, "MAINT", "<yellow>"));
		statusColors.put(ServerSelectorStatus.NOP, ConfigValues.string(statusColorSection, "NOP", "<gray>"));

		Map<ServerSelectorStatus, String> statusIcons = new LinkedHashMap<>();
		statusIcons.put(ServerSelectorStatus.ONLINE, ConfigValues.string(statusIconSection, "ONLINE", "✔"));
		statusIcons.put(ServerSelectorStatus.OFFLINE, ConfigValues.string(statusIconSection, "OFFLINE", "✘"));
		statusIcons.put(ServerSelectorStatus.MAINT, ConfigValues.string(statusIconSection, "MAINT", "⚠"));
		statusIcons.put(ServerSelectorStatus.NOP, ConfigValues.string(statusIconSection, "NOP", "🔒"));

		Map<String, ServerInfo> serverInfo = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : serverInfoSection.entrySet()) {
			String key = normalize(entry.getKey());
			if (key.isBlank()) {
				continue;
			}

			Map<String, Object> infoNode = ConfigValues.map(entry.getValue());
			String displayName = ConfigValues.string(infoNode, "display", key);
			String accentColor = ConfigValues.string(infoNode, "accent-color", "");
			String serverName = ConfigValues.string(infoNode, "server-name", key);
			serverInfo.put(key, new ServerInfo(displayName, accentColor, serverName));
		}

		Map<String, ServerDefinition> servers = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : serversSection.entrySet()) {
			Map<String, Object> item = ConfigValues.map(entry.getValue());
			String key = normalize(entry.getKey());
			String backendName = key;
			if (backendName.isBlank()) {
				continue;
			}

			Map<String, Object> infoNode = ConfigValues.map(serverInfoSection, backendName);

			String displayName = ConfigValues.string(
				infoNode,
				"display",
				ConfigValues.string(item, "display", backendName)
			);
			String accentColor = ConfigValues.string(
				infoNode,
				"accent-color",
				ConfigValues.string(item, "accent-color", "")
			);
			String permission = ConfigValues.string(item, "permission", "");
			String connectMessage = ConfigValues.string(item, "connect-message", "");
			Integer slot = ConfigValues.integerValue(item.get("slot"), null);
			Integer page = ConfigValues.integerValue(item.get("page"), null);
			String material = ConfigValues.string(item, "material", "");
			Map<ServerSelectorStatus, String> materialByStatus = parseMaterialByStatus(ConfigValues.map(item, "material-by-status"));
			Boolean glint = item.containsKey("glint") ? ConfigValues.booleanValue(item.get("glint"), false) : null;
			Map<ServerSelectorStatus, Boolean> glintByStatus = parseGlintByStatus(ConfigValues.map(item, "glint-by-status"));
			List<ConditionalOverride> conditionalOverrides = parseConditionalOverrides(item.get("conditional"));
			ServerTemplate serverTemplate = parseTemplate(ConfigValues.map(item, "template"), null);
			Map<ServerSelectorStatus, List<String>> descriptionByStatus = parseDescriptionByStatus(ConfigValues.map(item, "descriptions-by-status"));
			servers.put(normalize(backendName), new ServerDefinition(
				backendName,
				displayName,
				accentColor,
				permission,
				connectMessage,
				slot,
				page,
				material,
				materialByStatus,
				glint,
				glintByStatus,
				conditionalOverrides,
				description(backendName, descriptionsSection),
				descriptionByStatus,
				serverTemplate
			));
		}

		return new VelocityServerSelectorConfig(
			ConfigValues.booleanValue(section, "enabled", true),
			ConfigValues.booleanValue(section, "fail-on-server-command-override-failure", true),
			new SelectorDiagnostics(
				ConfigValues.booleanValue(diagnosticsSection, "enabled", true),
				ConfigValues.booleanValue(diagnosticsSection, "fail-on-validation-error", true),
				ConfigValues.booleanValue(diagnosticsSection, "unknown-placeholder-as-error", false)
			),
			ConfigValues.string(section, "title", "<gradient:#4C00E0:#CF115E>Chọn máy chủ</gradient>"),
			parseTemplate(templateSection, new ServerTemplate("<b>%server_display%</b>", List.of(), "%line%", List.of(), "", Map.of())),
			ConfigValues.string(messages, "opening", "<yellow>Đang mở danh sách máy chủ...</yellow>"),
			ConfigValues.string(messages, "player-only", "<red>❌ Lệnh này chỉ dành cho người chơi.</red>"),
			ConfigValues.string(messages, "not-found", "<red>❌ Không tìm thấy máy chủ %server_name%.</red>"),
			ConfigValues.string(messages, "offline", "<red>❌ %server_display% hiện đang ngoại tuyến.</red>"),
			ConfigValues.string(messages, "maint", "<yellow>⚠ %server_display% đang bảo trì.</yellow>"),
			ConfigValues.string(messages, "no-permission", "<red>❌ Bạn không có quyền vào %server_display%.</red>"),
			ConfigValues.string(messages, "connecting", "<yellow>⌛ Đang kết nối đến %server_display%...</yellow>"),
			Map.copyOf(statusColors),
			Map.copyOf(statusIcons),
			Map.copyOf(servers),
			Map.copyOf(serverInfo)
		);
	}

	public ServerDefinition server(String backendName) {
		if (backendName == null || backendName.isBlank()) {
			return null;
		}
		return servers.get(normalize(backendName));
	}

	public String color(ServerSelectorStatus status) {
		return statusColors.getOrDefault(status, "<white>");
	}

	public String icon(ServerSelectorStatus status) {
		return statusIcons.getOrDefault(status, "●");
	}

	public ServerInfo serverInfo(String backendName) {
		if (backendName == null || backendName.isBlank()) {
			return null;
		}
		return serverInfo.get(normalize(backendName));
	}

	public BackendMetadata backendMetadata(String backendName) {
		String normalized = normalize(backendName);
		if (normalized.isBlank()) {
			return new BackendMetadata("", "", "").sanitize();
		}

		ServerDefinition definition = server(normalized);
		ServerInfo info = serverInfo(normalized);
		String displayName = "";
		String accentColor = "";
		String serverName = normalized;

		if (definition != null) {
			displayName = definition.displayName();
			accentColor = definition.accentColor();
		}

		if ((displayName == null || displayName.isBlank()) && info != null) {
			displayName = info.displayName();
		}

		if ((accentColor == null || accentColor.isBlank()) && info != null) {
			accentColor = info.accentColor();
		}

		if (info != null && info.serverName() != null && !info.serverName().isBlank()) {
			serverName = info.serverName();
		}

		return new BackendMetadata(normalized, displayName, accentColor, serverName).sanitize();
	}

	public List<String> knownServerNames() {
		java.util.Set<String> names = new java.util.LinkedHashSet<>(serverInfo.keySet());
		names.addAll(servers.keySet());
		return List.copyOf(names);
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static List<String> description(String backendName, Map<String, Object> descriptionsSection) {
		String key = backendName == null ? "" : backendName.trim();
		Object raw = descriptionsSection.get(key);
		if (raw instanceof Map<?, ?> map) {
			raw = map.get("default");
		}
		if (raw instanceof Iterable<?> iterable) {
			List<String> lines = new ArrayList<>();
			for (Object item : iterable) {
				if (item == null) {
					continue;
				}
				lines.add(String.valueOf(item));
			}
			return List.copyOf(lines);
		}
		return List.of();
	}

	private static Map<ServerSelectorStatus, List<String>> parseDescriptionByStatus(Map<String, Object> section) {
		Map<ServerSelectorStatus, List<String>> byStatus = new LinkedHashMap<>();
		for (ServerSelectorStatus status : ServerSelectorStatus.values()) {
			Object raw = section.get(status.name());
			if (!(raw instanceof Iterable<?> iterable)) {
				continue;
			}
			List<String> lines = new ArrayList<>();
			for (Object item : iterable) {
				if (item == null) {
					continue;
				}
				lines.add(String.valueOf(item));
			}
			byStatus.put(status, List.copyOf(lines));
		}
		return Map.copyOf(byStatus);
	}

	private static Map<ServerSelectorStatus, String> parseMaterialByStatus(Map<String, Object> section) {
		Map<ServerSelectorStatus, String> byStatus = new LinkedHashMap<>();
		for (ServerSelectorStatus status : ServerSelectorStatus.values()) {
			String material = ConfigValues.string(section, status.name(), "");
			if (material.isBlank()) {
				continue;
			}
			byStatus.put(status, material);
		}
		return Map.copyOf(byStatus);
	}

	private static Map<ServerSelectorStatus, Boolean> parseGlintByStatus(Map<String, Object> section) {
		Map<ServerSelectorStatus, Boolean> byStatus = new LinkedHashMap<>();
		for (ServerSelectorStatus status : ServerSelectorStatus.values()) {
			if (!section.containsKey(status.name())) {
				continue;
			}
			byStatus.put(status, ConfigValues.booleanValue(section.get(status.name()), false));
		}
		return Map.copyOf(byStatus);
	}

	private static List<ConditionalOverride> parseConditionalOverrides(Object raw) {
		if (!(raw instanceof Iterable<?> iterable)) {
			return List.of();
		}

		List<ConditionalOverride> overrides = new ArrayList<>();
		for (Object node : iterable) {
			Map<String, Object> section = ConfigValues.map(node);
			if (section.isEmpty()) {
				continue;
			}

			String when = ConfigValues.string(section, "when", ConfigValues.string(section, "condition", ""));
			if (when.isBlank()) {
				continue;
			}

			String material = section.containsKey("material") ? ConfigValues.string(section.get("material"), "") : null;
			if (material != null && material.isBlank()) {
				material = null;
			}

			Boolean glint = section.containsKey("glint") ? ConfigValues.booleanValue(section.get("glint"), false) : null;
			List<String> description = section.containsKey("description") ? parseTextLines(section.get("description"), List.of()) : null;
			TemplateOverride template = parseTemplateOverride(ConfigValues.map(section, "template"));

			overrides.add(new ConditionalOverride(when, material, glint, description, template));
		}

		return List.copyOf(overrides);
	}

	private static TemplateOverride parseTemplateOverride(Map<String, Object> section) {
		if (section.isEmpty()) {
			return null;
		}

		String nameOverride = section.containsKey("name") ? ConfigValues.string(section.get("name"), "") : null;
		List<String> headerOverride = section.containsKey("header") ? parseTextLines(section.get("header"), List.of()) : null;
		String bodyOverride = section.containsKey("body-line") ? ConfigValues.string(section.get("body-line"), "") : null;
		List<String> footerOverride = section.containsKey("footer") ? parseTextLines(section.get("footer"), List.of()) : null;

		if (nameOverride == null && headerOverride == null && bodyOverride == null && footerOverride == null) {
			return null;
		}

		return new TemplateOverride(nameOverride, headerOverride, bodyOverride, footerOverride);
	}

	private static ServerTemplate parseTemplate(Map<String, Object> section, ServerTemplate fallback) {
		if (section.isEmpty()) {
			return fallback;
		}

		String nameFallback = fallback == null ? "<b>%server_display%</b>" : fallback.name();
		String bodyFallback = fallback == null ? "%line%" : fallback.bodyLine();
		List<String> headerFallback = fallback == null ? List.of() : fallback.headerLines();
		List<String> footerFallback = fallback == null ? List.of() : fallback.footerLines();
		String materialFallback = fallback == null ? "" : fallback.material();
		Map<ServerSelectorStatus, TemplateOverride> fallbackByStatus = fallback == null ? Map.of() : fallback.byStatus();

		Map<ServerSelectorStatus, TemplateOverride> byStatus = new LinkedHashMap<>();
		Map<String, Object> byStatusSection = ConfigValues.map(section, "by-status");
		for (ServerSelectorStatus status : ServerSelectorStatus.values()) {
			Map<String, Object> statusNode = ConfigValues.map(byStatusSection, status.name());
			if (statusNode.isEmpty()) {
				TemplateOverride inherited = fallbackByStatus.get(status);
				if (inherited != null) {
					byStatus.put(status, inherited);
				}
				continue;
			}

			String nameOverride = statusNode.containsKey("name") ? ConfigValues.string(statusNode.get("name"), "") : null;
			String bodyOverride = statusNode.containsKey("body-line") ? ConfigValues.string(statusNode.get("body-line"), "") : null;
			List<String> headerOverride = statusNode.containsKey("header") ? parseTextLines(statusNode.get("header"), List.of()) : null;
			List<String> footerOverride = statusNode.containsKey("footer") ? parseTextLines(statusNode.get("footer"), List.of()) : null;
			byStatus.put(status, new TemplateOverride(nameOverride, headerOverride, bodyOverride, footerOverride));
		}

		return new ServerTemplate(
			ConfigValues.string(section, "name", nameFallback),
			parseTextLines(section.get("header"), headerFallback),
			ConfigValues.string(section, "body-line", bodyFallback),
			parseTextLines(section.get("footer"), footerFallback),
			ConfigValues.string(section, "material", materialFallback),
			Map.copyOf(byStatus)
		);
	}

	private static List<String> parseTextLines(Object raw, List<String> fallback) {
		if (raw == null) {
			return fallback;
		}

		if (raw instanceof Iterable<?> iterable) {
			List<String> lines = new ArrayList<>();
			for (Object item : iterable) {
				if (item == null) {
					continue;
				}
				lines.add(String.valueOf(item));
			}
			return List.copyOf(lines);
		}

		String text = String.valueOf(raw);
		if (text.isEmpty()) {
			return List.of();
		}

		String[] parts = text.split("\\r?\\n");
		List<String> lines = new ArrayList<>(parts.length);
		for (String part : parts) {
			lines.add(part);
		}
		return List.copyOf(lines);
	}

	public record ServerTemplate(
		String name,
		List<String> headerLines,
		String bodyLine,
		List<String> footerLines,
		String material,
		Map<ServerSelectorStatus, TemplateOverride> byStatus
	) {
	}

	public record TemplateOverride(
		String name,
		List<String> headerLines,
		String bodyLine,
		List<String> footerLines
	) {
	}

	public record ServerDefinition(
		String backendName,
		String displayName,
		String accentColor,
		String permission,
		String connectMessage,
		Integer slot,
		Integer page,
		String material,
		Map<ServerSelectorStatus, String> materialByStatus,
		Boolean glint,
		Map<ServerSelectorStatus, Boolean> glintByStatus,
		List<ConditionalOverride> conditional,
		List<String> description,
		Map<ServerSelectorStatus, List<String>> descriptionByStatus,
		ServerTemplate template
	) {
	}

	public record ConditionalOverride(
		String when,
		String material,
		Boolean glint,
		List<String> description,
		TemplateOverride template
	) {
	}

	public record SelectorDiagnostics(
		boolean enabled,
		boolean failOnValidationError,
		boolean unknownPlaceholderAsError
	) {
	}

	public record ServerInfo(
		String displayName,
		String accentColor,
		String serverName
	) {
	}
}
