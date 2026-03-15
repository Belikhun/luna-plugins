package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.config.ConfigValues;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record VelocityServerSelectorConfig(
	boolean enabled,
	boolean failOnServerOverrideFailure,
	SelectorDiagnostics diagnostics,
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
			String descriptionKey = ConfigValues.string(item, "description-key", backendName);
			Integer slot = ConfigValues.integerValue(item.get("slot"), null);
			String material = ConfigValues.string(item, "material", "");
			servers.put(normalize(backendName), new ServerDefinition(backendName, displayName, accentColor, permission, connectMessage, descriptionKey, slot, material, description(descriptionKey, descriptionsSection)));
		}

		return new VelocityServerSelectorConfig(
			ConfigValues.booleanValue(section, "enabled", true),
			ConfigValues.booleanValue(section, "fail-on-server-command-override-failure", true),
			new SelectorDiagnostics(
				ConfigValues.booleanValue(diagnosticsSection, "enabled", true),
				ConfigValues.booleanValue(diagnosticsSection, "fail-on-validation-error", true),
				ConfigValues.booleanValue(diagnosticsSection, "unknown-placeholder-as-error", false)
			),
			new ServerTemplate(
				ConfigValues.string(templateSection, "name", "<b>%server_display%</b>"),
				ConfigValues.string(templateSection, "header", ""),
				ConfigValues.string(templateSection, "body-line", "%line%"),
				ConfigValues.string(templateSection, "footer", "")
			),
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

	private static java.util.List<String> description(String key, Map<String, Object> descriptionsSection) {
		Object raw = descriptionsSection.get(key);
		if (raw instanceof Iterable<?> iterable) {
			java.util.List<String> lines = new java.util.ArrayList<>();
			for (Object item : iterable) {
				if (item == null) {
					continue;
				}
				lines.add(String.valueOf(item));
			}
			return java.util.List.copyOf(lines);
		}
		return java.util.List.of();
	}

	public record ServerTemplate(
		String name,
		String header,
		String bodyLine,
		String footer
	) {
	}

	public record ServerDefinition(
		String backendName,
		String displayName,
		String accentColor,
		String permission,
		String connectMessage,
		String descriptionKey,
		Integer slot,
		String material,
		java.util.List<String> description
	) {
	}

	public record SelectorDiagnostics(
		boolean enabled,
		boolean failOnValidationError,
		boolean unknownPlaceholderAsError
	) {
	}
}
