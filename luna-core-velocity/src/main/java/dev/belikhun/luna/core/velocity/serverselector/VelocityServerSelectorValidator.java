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
		"online",
		"max",
		"uptime",
		"tps",
		"version",
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
		Map<String, Object> descriptionsSection = ConfigValues.map(section, "descriptions");

		if (config.enabled() && config.servers().isEmpty()) {
			errors.add("server-selector đang bật nhưng không có server nào được khai báo.");
		}

		Set<String> seenBackends = new HashSet<>();
		Set<Integer> seenSlots = new HashSet<>();
		Set<String> usedDescriptionKeys = new LinkedHashSet<>();

		for (Map.Entry<String, Object> entry : serversSection.entrySet()) {
			String nodeName = entry.getKey();
			Map<String, Object> serverNode = ConfigValues.map(entry.getValue());
			String backendName = ConfigValues.string(serverNode, "backend-name", nodeName).trim();
			if (backendName.isBlank()) {
				errors.add("Server node '" + nodeName + "' có backend-name rỗng.");
				continue;
			}

			String normalizedBackend = normalize(backendName);
			if (!seenBackends.add(normalizedBackend)) {
				errors.add("Trùng backend-name sau khi normalize: '" + backendName + "'.");
			}

			String permission = ConfigValues.string(serverNode, "permission", "");
			if (!permission.isBlank() && !PERMISSION_PATTERN.matcher(permission.toLowerCase(Locale.ROOT)).matches()) {
				warnings.add("Permission có định dạng lạ tại server '" + backendName + "': " + permission);
			}

			Integer slot = ConfigValues.integerValue(serverNode.get("slot"), null);
			if (slot != null) {
				if (slot < 0 || slot > 53) {
					errors.add("Slot không hợp lệ tại server '" + backendName + "': " + slot + " (phải trong [0..53]).");
				} else if (!seenSlots.add(slot)) {
					errors.add("Trùng slot trong server-selector: " + slot);
				}
			}

			String material = ConfigValues.string(serverNode, "material", "");
			if (!material.isBlank() && !MATERIAL_PATTERN.matcher(material).matches()) {
				warnings.add("Material có định dạng lạ tại server '" + backendName + "': " + material);
			}

			String descriptionKey = ConfigValues.string(serverNode, "description-key", backendName).trim();
			if (descriptionKey.isBlank()) {
				descriptionKey = backendName;
			}
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
		}

		for (String key : descriptionsSection.keySet()) {
			if (!usedDescriptionKeys.contains(key)) {
				warnings.add("Descriptions key không được server nào tham chiếu: '" + key + "'.");
			}
		}

		validateTemplatePlaceholders("template.name", config.template().name(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("template.header", config.template().header(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("template.body-line", config.template().bodyLine(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("template.footer", config.template().footer(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);

		validateTemplatePlaceholders("messages.opening", config.openingMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.player-only", config.playerOnlyMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.not-found", config.notFoundMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.offline", config.offlineMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.maint", config.maintMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.no-permission", config.noPermissionMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);
		validateTemplatePlaceholders("messages.connecting", config.connectingMessage(), config.diagnostics().unknownPlaceholderAsError(), errors, warnings);

		for (Map.Entry<String, Object> entry : descriptionsSection.entrySet()) {
			Object raw = entry.getValue();
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
}
