package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.config.ConfigValues;

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
	Map<String, ServerDefinition> servers
) {
	public static VelocityServerSelectorConfig from(Map<String, Object> rootConfig) {
		Map<String, Object> section = ConfigValues.map(rootConfig, "server-selector");
		Map<String, Object> templateSection = ConfigValues.map(section, "template");
		Map<String, Object> messages = ConfigValues.map(section, "messages");
		Map<String, Object> statusColorSection = ConfigValues.map(section, "status-colors");
		Map<String, Object> serversSection = ConfigValues.map(section, "servers");
		Map<String, Object> descriptionsSection = ConfigValues.map(section, "descriptions");
		Map<String, Object> diagnosticsSection = ConfigValues.map(section, "diagnostics");

		Map<ServerSelectorStatus, String> statusColors = new LinkedHashMap<>();
		statusColors.put(ServerSelectorStatus.ONLINE, ConfigValues.string(statusColorSection, "ONLINE", "<green>"));
		statusColors.put(ServerSelectorStatus.OFFLINE, ConfigValues.string(statusColorSection, "OFFLINE", "<red>"));
		statusColors.put(ServerSelectorStatus.MAINT, ConfigValues.string(statusColorSection, "MAINT", "<yellow>"));
		statusColors.put(ServerSelectorStatus.NOP, ConfigValues.string(statusColorSection, "NOP", "<gray>"));

		Map<String, ServerDefinition> servers = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : serversSection.entrySet()) {
			Map<String, Object> item = ConfigValues.map(entry.getValue());
			String key = entry.getKey().trim();
			String backendName = ConfigValues.string(item, "backend-name", key).trim();
			if (backendName.isBlank()) {
				continue;
			}

			String displayName = ConfigValues.string(item, "display", backendName);
			String accentColor = ConfigValues.string(item, "accent-color", "");
			String permission = ConfigValues.string(item, "permission", "");
			String connectMessage = ConfigValues.string(item, "connect-message", "");
			Integer slot = ConfigValues.integerValue(item.get("slot"), null);
			Integer page = ConfigValues.integerValue(item.get("page"), null);
			String material = ConfigValues.string(item, "material", "");
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
			parseTemplate(templateSection, new ServerTemplate("<b>%server_display%</b>", List.of(), "%line%", List.of(), Map.of())),
			ConfigValues.string(messages, "opening", "<yellow>Đang mở danh sách máy chủ...</yellow>"),
			ConfigValues.string(messages, "player-only", "<red>❌ Lệnh này chỉ dành cho người chơi.</red>"),
			ConfigValues.string(messages, "not-found", "<red>❌ Không tìm thấy máy chủ %server_name%.</red>"),
			ConfigValues.string(messages, "offline", "<red>❌ %server_display% hiện đang ngoại tuyến.</red>"),
			ConfigValues.string(messages, "maint", "<yellow>⚠ %server_display% đang bảo trì.</yellow>"),
			ConfigValues.string(messages, "no-permission", "<red>❌ Bạn không có quyền vào %server_display%.</red>"),
			ConfigValues.string(messages, "connecting", "<yellow>⌛ Đang kết nối đến %server_display%...</yellow>"),
			Map.copyOf(statusColors),
			Map.copyOf(servers)
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

	private static ServerTemplate parseTemplate(Map<String, Object> section, ServerTemplate fallback) {
		if (section.isEmpty()) {
			return fallback;
		}

		String nameFallback = fallback == null ? "<b>%server_display%</b>" : fallback.name();
		String bodyFallback = fallback == null ? "%line%" : fallback.bodyLine();
		List<String> headerFallback = fallback == null ? List.of() : fallback.headerLines();
		List<String> footerFallback = fallback == null ? List.of() : fallback.footerLines();
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
		List<String> description,
		Map<ServerSelectorStatus, List<String>> descriptionByStatus,
		ServerTemplate template
	) {
	}

	public record SelectorDiagnostics(
		boolean enabled,
		boolean failOnValidationError,
		boolean unknownPlaceholderAsError
	) {
	}
}
