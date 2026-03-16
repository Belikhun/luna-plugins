package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.config.ConfigValues;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VelocityServerSelectorValidator {
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([a-zA-Z0-9_]+)%");
	private static final Pattern PERMISSION_PATTERN = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");
	private static final Pattern MATERIAL_PATTERN = Pattern.compile("[A-Z0-9_]+");
	private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
		"server_name",
		"server_display",
		"server_accent_color",
		"server_status",
		"server_status_color",
		"server_status_icon",
		"online",
		"max",
		"uptime",
		"tps",
		"cpu_usage",
		"ram_used_mb",
		"ram_max_mb",
		"ram_percent",
		"latency_ms",
		"version",
		"server_version",
		"server_version_full",
		"motd",
		"player_name",
		"line"
	);

	private VelocityServerSelectorValidator() {
	}

	public static VelocityServerSelectorValidationReport validate(
		Map<String, Object> rootConfig,
		VelocityServerSelectorConfig config
	) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		Map<String, Object> section = ConfigValues.map(rootConfig, "server-selector");
		Map<String, Object> serversSection = ConfigValues.map(section, "servers");
		Map<String, Object> serverInfoSection = ConfigValues.map(rootConfig, "server-info");
		Map<String, Object> descriptionsSection = ConfigValues.map(section, "descriptions");

		if (config.enabled() && config.servers().isEmpty()) {
			errors.add("server-selector đang bật nhưng không có server nào được khai báo.");
		}

		Set<String> seenBackends = new HashSet<>();
		Set<String> seenSlotPages = new HashSet<>();
		Set<String> usedDescriptionKeys = new LinkedHashSet<>();
		Set<String> usedServerInfoKeys = new LinkedHashSet<>();

		for (Map.Entry<String, Object> entry : serversSection.entrySet()) {
			String nodeName = entry.getKey();
			Map<String, Object> serverNode = ConfigValues.map(entry.getValue());
			String backendName = nodeName == null ? "" : nodeName.trim();
			if (backendName.isBlank()) {
				errors.add("Server node có key rỗng trong server-selector.servers.");
				continue;
			}

			String legacyBackend = ConfigValues.string(serverNode, "backend-name", "").trim();
			if (!legacyBackend.isBlank()) {
				if (!normalize(legacyBackend).equals(normalize(backendName))) {
					errors.add("servers.'" + nodeName + "'.backend-name phải trùng key server (legacy field): " + legacyBackend);
				} else {
					warnings.add("servers.'" + nodeName + "'.backend-name là legacy field, có thể xóa.");
				}
			}

			String normalizedBackend = normalize(backendName);
			if (!seenBackends.add(normalizedBackend)) {
				errors.add("Trùng server key sau khi normalize: '" + backendName + "'.");
			}

			Map<String, Object> infoNode = ConfigValues.map(serverInfoSection, normalizedBackend);
			if (infoNode.isEmpty()) {
				warnings.add("Thiếu root.server-info.'" + normalizedBackend + "'. Sẽ dùng fallback từ servers node (legacy). ");
			} else {
				usedServerInfoKeys.add(normalizedBackend);
			}

			String permission = ConfigValues.string(serverNode, "permission", "");
			if (!permission.isBlank() && !PERMISSION_PATTERN.matcher(permission.toLowerCase(Locale.ROOT)).matches()) {
				warnings.add("Permission có định dạng lạ tại server '" + backendName + "': " + permission);
			}

			Integer slot = ConfigValues.integerValue(serverNode.get("slot"), null);
			Integer page = ConfigValues.integerValue(serverNode.get("page"), null);
			if (page != null && page < 1) {
				errors.add("Page không hợp lệ tại server '" + backendName + "': " + page + " (phải >= 1).");
			}
			if (slot != null) {
				if (slot < 0 || slot > 44) {
					errors.add("Slot không hợp lệ tại server '" + backendName + "': " + slot + " (phải trong [0..44]).");
				}
				if (page == null) {
					warnings.add("Server '" + backendName + "' có slot nhưng thiếu page. Cần khai báo cả slot + page để cố định vị trí.");
				} else {
					String key = page + ":" + slot;
					if (!seenSlotPages.add(key)) {
						errors.add("Trùng vị trí server-selector tại page=" + page + ", slot=" + slot + ".");
					}
				}
			} else if (page != null) {
				warnings.add("Server '" + backendName + "' có page nhưng thiếu slot. Cần khai báo cả slot + page để cố định vị trí.");
			}

			String material = ConfigValues.string(serverNode, "material", "");
			if (!material.isBlank() && !MATERIAL_PATTERN.matcher(material).matches()) {
				warnings.add("Material có định dạng lạ tại server '" + backendName + "': " + material);
			}

			Map<String, Object> materialByStatus = ConfigValues.map(serverNode, "material-by-status");
			for (Map.Entry<String, Object> materialEntry : materialByStatus.entrySet()) {
				String statusKey = materialEntry.getKey() == null ? "" : materialEntry.getKey().trim();
				if (parseStatus(statusKey) == null) {
					warnings.add("Status không hợp lệ tại servers.'" + nodeName + "'.material-by-status." + statusKey + ".");
					continue;
				}

				String statusMaterial = ConfigValues.string(materialEntry.getValue(), "");
				if (!statusMaterial.isBlank() && !MATERIAL_PATTERN.matcher(statusMaterial).matches()) {
					warnings.add("Material có định dạng lạ tại servers.'" + nodeName + "'.material-by-status." + statusKey + ": " + statusMaterial);
				}
			}

			if (serverNode.containsKey("glint") && !isBooleanLike(serverNode.get("glint"))) {
				warnings.add("Giá trị glint không hợp lệ tại server '" + backendName + "'. Chỉ hỗ trợ true/false/yes/no/1/0.");
			}

			Map<String, Object> glintByStatus = ConfigValues.map(serverNode, "glint-by-status");
			for (Map.Entry<String, Object> glintEntry : glintByStatus.entrySet()) {
				String statusKey = glintEntry.getKey() == null ? "" : glintEntry.getKey().trim();
				if (parseStatus(statusKey) == null) {
					warnings.add("Status không hợp lệ tại servers.'" + nodeName + "'.glint-by-status." + statusKey + ".");
					continue;
				}

				if (!isBooleanLike(glintEntry.getValue())) {
					warnings.add("Giá trị glint không hợp lệ tại servers.'" + nodeName + "'.glint-by-status." + statusKey + ". Chỉ hỗ trợ true/false/yes/no/1/0.");
				}
			}

			Object conditionalRaw = serverNode.get("conditional");
			if (conditionalRaw != null && !(conditionalRaw instanceof Iterable<?> conditionList)) {
				warnings.add("servers.'" + nodeName + "'.conditional phải là danh sách.");
			} else if (conditionalRaw instanceof Iterable<?> conditionList) {
				int conditionIndex = 0;
				for (Object conditionNode : conditionList) {
					Map<String, Object> condition = ConfigValues.map(conditionNode);
					if (condition.isEmpty()) {
						warnings.add("servers.'" + nodeName + "'.conditional[" + conditionIndex + "] không phải object hợp lệ.");
						conditionIndex++;
						continue;
					}

					String when = ConfigValues.string(condition, "when", ConfigValues.string(condition, "condition", ""));
					if (when.isBlank()) {
						warnings.add("servers.'" + nodeName + "'.conditional[" + conditionIndex + "] thiếu when/condition.");
					}

					if (condition.containsKey("material")) {
						String conditionMaterial = ConfigValues.string(condition.get("material"), "");
						if (!conditionMaterial.isBlank() && !MATERIAL_PATTERN.matcher(conditionMaterial).matches()) {
							warnings.add("Material có định dạng lạ tại servers.'" + nodeName + "'.conditional[" + conditionIndex + "].material: " + conditionMaterial);
						}
					}

					if (condition.containsKey("glint") && !isBooleanLike(condition.get("glint"))) {
						warnings.add("Giá trị glint không hợp lệ tại servers.'" + nodeName + "'.conditional[" + conditionIndex + "].glint.");
					}

					if (condition.containsKey("description")) {
						Object descriptionRaw = condition.get("description");
						if (!(descriptionRaw instanceof Iterable<?>) && !(descriptionRaw instanceof String)) {
							warnings.add("servers.'" + nodeName + "'.conditional[" + conditionIndex + "].description phải là string hoặc danh sách.");
						}
					}

					if (condition.containsKey("template")) {
						validateTemplateBlockPlaceholders(
							"servers." + nodeName + ".conditional[" + conditionIndex + "].template",
							ConfigValues.map(condition, "template"),
							config.diagnostics().unknownPlaceholderAsError(),
							errors,
							warnings
						);
					}

					conditionIndex++;
				}
			}

			String display = ConfigValues.string(infoNode, "display", ConfigValues.string(serverNode, "display", ""));
			if (display.isBlank()) {
				errors.add("Thiếu display cho server '" + backendName + "' (khai báo tại root.server-info). ");
			}

			String accentColor = ConfigValues.string(infoNode, "accent-color", ConfigValues.string(serverNode, "accent-color", ""));
			if (accentColor.isBlank()) {
				warnings.add("Thiếu accent-color cho server '" + backendName + "'.");
			}

			String descriptionKey = backendName.trim();
			usedDescriptionKeys.add(descriptionKey);
			if (!descriptionsSection.containsKey(descriptionKey)) {
				errors.add("Thiếu descriptions.'" + descriptionKey + "' cho server '" + backendName + "'.");
			}

			validateTemplatePlaceholders(
				"servers." + nodeName + ".connect-message",
				ConfigValues.string(serverNode, "connect-message", ""),
				config.diagnostics().unknownPlaceholderAsError(),
				errors,
				warnings
			);

			Map<String, Object> serverTemplate = ConfigValues.map(serverNode, "template");
			validateTemplateBlockPlaceholders(
				"servers." + nodeName + ".template",
				serverTemplate,
				config.diagnostics().unknownPlaceholderAsError(),
				errors,
				warnings
			);

			Map<String, Object> serverDescByStatus = ConfigValues.map(serverNode, "descriptions-by-status");
			for (Map.Entry<String, Object> statusEntry : serverDescByStatus.entrySet()) {
				Object raw = statusEntry.getValue();
				if (!(raw instanceof Iterable<?> iterable)) {
					warnings.add("servers." + nodeName + ".descriptions-by-status." + statusEntry.getKey() + " không phải danh sách.");
					continue;
				}
				int index = 0;
				for (Object line : iterable) {
					validateTemplatePlaceholders(
						"servers." + nodeName + ".descriptions-by-status." + statusEntry.getKey() + "[" + index + "]",
						line == null ? "" : String.valueOf(line),
						config.diagnostics().unknownPlaceholderAsError(),
						errors,
						warnings
					);
					index++;
				}
			}
		}

		for (String key : descriptionsSection.keySet()) {
			if (!usedDescriptionKeys.contains(key)) {
				warnings.add("Descriptions key không được server nào tham chiếu: '" + key + "'.");
			}
		}

		for (String key : serverInfoSection.keySet()) {
			String normalized = normalize(key);
			if (!serversSection.containsKey(key) && !serversSection.containsKey(normalized)) {
				warnings.add("root.server-info key không có server tương ứng trong servers: '" + key + "'.");
			}
			if (!usedServerInfoKeys.contains(normalized) && serversSection.containsKey(key)) {
				usedServerInfoKeys.add(normalized);
			}
		}

		validateTemplatePlaceholders("template.name", config.template().name(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		if (!config.template().material().isBlank() && !MATERIAL_PATTERN.matcher(config.template().material()).matches()) {
			warnings.add("Material có định dạng lạ tại template.material: " + config.template().material());
		}
		validateTemplatePlaceholders("title", config.guiTitle(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplateLines("template.header", config.template().headerLines(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("template.body-line", config.template().bodyLine(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplateLines("template.footer", config.template().footerLines(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		for (Map.Entry<ServerSelectorStatus, VelocityServerSelectorConfig.TemplateOverride> entry : config.template().byStatus().entrySet()) {
			String path = "template.by-status." + entry.getKey().name();
			VelocityServerSelectorConfig.TemplateOverride override = entry.getValue();
			if (override.name() != null) {
				validateTemplatePlaceholders(path + ".name", override.name(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
			}
			if (override.bodyLine() != null) {
				validateTemplatePlaceholders(path + ".body-line", override.bodyLine(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
			}
			if (override.headerLines() != null) {
				validateTemplateLines(path + ".header", override.headerLines(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
			}
			if (override.footerLines() != null) {
				validateTemplateLines(path + ".footer", override.footerLines(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
			}
		}

		validateTemplatePlaceholders("messages.opening", config.openingMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.player-only", config.playerOnlyMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.not-found", config.notFoundMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.offline", config.offlineMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.maint", config.maintMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.no-permission", config.noPermissionMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.connecting", config.connectingMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);

		for (Map.Entry<String, Object> entry : descriptionsSection.entrySet()) {
			Object raw = entry.getValue();
			if (raw instanceof Map<?, ?> mapNode) {
				for (Map.Entry<?, ?> statusEntry : mapNode.entrySet()) {
					Object statusRaw = statusEntry.getValue();
					if (!(statusRaw instanceof Iterable<?> statusLines)) {
						warnings.add("descriptions.'" + entry.getKey() + "." + statusEntry.getKey() + "' không phải danh sách.");
						continue;
					}
					int index = 0;
					for (Object line : statusLines) {
						validateTemplatePlaceholders(
							"descriptions." + entry.getKey() + "." + statusEntry.getKey() + "[" + index + "]",
							line == null ? "" : String.valueOf(line),
							config.diagnostics().unknownPlaceholderAsError(),
							errors,
							warnings
						);
						index++;
					}
				}
				continue;
			}

			if (!(raw instanceof Iterable<?> iterable)) {
				warnings.add("descriptions.'" + entry.getKey() + "' không phải danh sách.");
				continue;
			}

			int index = 0;
			for (Object line : iterable) {
				validateTemplatePlaceholders(
					"descriptions." + entry.getKey() + "[" + index + "]",
					line == null ? "" : String.valueOf(line),
					config.diagnostics().unknownPlaceholderAsError(),
					errors,
					warnings
				);
				index++;
			}
		}

		return new VelocityServerSelectorValidationReport(List.copyOf(errors), List.copyOf(warnings));
	}

	private static void validateTemplateLines(
		String path,
		List<String> lines,
		boolean unknownAsError,
		List<String> errors,
		List<String> warnings
	) {
		for (int i = 0; i < lines.size(); i++) {
			validateTemplatePlaceholders(path + "[" + i + "]", lines.get(i), unknownAsError, errors, warnings);
		}
	}

	private static void validateTemplateBlockPlaceholders(
		String basePath,
		Map<String, Object> block,
		boolean unknownAsError,
		List<String> errors,
		List<String> warnings
	) {
		if (block.isEmpty()) {
			return;
		}
		validateTemplatePlaceholders(basePath + ".name", ConfigValues.string(block, "name", ""), unknownAsError, errors, warnings);
		validateTemplatePlaceholders(basePath + ".body-line", ConfigValues.string(block, "body-line", ""), unknownAsError, errors, warnings);
		String templateMaterial = ConfigValues.string(block, "material", "");
		if (!templateMaterial.isBlank() && !MATERIAL_PATTERN.matcher(templateMaterial).matches()) {
			warnings.add("Material có định dạng lạ tại " + basePath + ".material: " + templateMaterial);
		}

		Object header = block.get("header");
		if (header instanceof Iterable<?> iterable) {
			int i = 0;
			for (Object line : iterable) {
				validateTemplatePlaceholders(basePath + ".header[" + i + "]", line == null ? "" : String.valueOf(line), unknownAsError, errors, warnings);
				i++;
			}
		} else {
			validateTemplatePlaceholders(basePath + ".header", ConfigValues.string(header, ""), unknownAsError, errors, warnings);
		}

		Object footer = block.get("footer");
		if (footer instanceof Iterable<?> iterable) {
			int i = 0;
			for (Object line : iterable) {
				validateTemplatePlaceholders(basePath + ".footer[" + i + "]", line == null ? "" : String.valueOf(line), unknownAsError, errors, warnings);
				i++;
			}
		} else {
			validateTemplatePlaceholders(basePath + ".footer", ConfigValues.string(footer, ""), unknownAsError, errors, warnings);
		}

		Map<String, Object> byStatus = ConfigValues.map(block, "by-status");
		for (Map.Entry<String, Object> entry : byStatus.entrySet()) {
			validateTemplateBlockPlaceholders(basePath + ".by-status." + entry.getKey(), ConfigValues.map(entry.getValue()), unknownAsError, errors, warnings);
		}
	}

	private static void validateTemplatePlaceholders(
		String path,
		String value,
		boolean unknownAsError,
		List<String> errors,
		List<String> warnings
	) {
		if (value == null || value.isBlank()) {
			return;
		}

		Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
		while (matcher.find()) {
			String key = normalize(matcher.group(1));
			if (KNOWN_PLACEHOLDERS.contains(key)) {
				continue;
			}

			String message = "Placeholder chưa được định nghĩa tại '" + path + "': %" + key + "%";
			if (unknownAsError) {
				errors.add(message);
			} else {
				warnings.add(message);
			}
		}
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static ServerSelectorStatus parseStatus(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return ServerSelectorStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static boolean isBooleanLike(Object value) {
		if (value instanceof Boolean) {
			return true;
		}
		if (value == null) {
			return false;
		}

		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		return "true".equals(text)
			|| "false".equals(text)
			|| "yes".equals(text)
			|| "no".equals(text)
			|| "1".equals(text)
			|| "0".equals(text);
	}
}
