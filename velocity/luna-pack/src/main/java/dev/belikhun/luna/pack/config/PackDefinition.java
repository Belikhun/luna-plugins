package dev.belikhun.luna.pack.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record PackDefinition(
	String name,
	String filename,
	int priority,
	boolean required,
	boolean enabled,
	List<String> servers,
	Path sourceFile
) {
	public String normalizedName() {
		return name.toLowerCase(Locale.ROOT);
	}

	public boolean matchesServer(String serverName) {
		String normalized = serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT);
		boolean included = false;
		for (String server : servers) {
			String normalizedRule = normalizeServerRule(server);
			if (normalizedRule == null) {
				continue;
			}

			boolean excluded = normalizedRule.startsWith("!");
			String rule = excluded ? normalizedRule.substring(1) : normalizedRule;
			if (!matchesRule(rule, normalized)) {
				continue;
			}

			if (excluded) {
				return false;
			}

			included = true;
		}
		return included;
	}

	public static String normalizeServerRule(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return null;
		}

		boolean excluded = normalized.startsWith("!");
		String rule = excluded ? normalized.substring(1).trim() : normalized;
		if (rule.isBlank()) {
			return null;
		}
		if (rule.equals("all")) {
			rule = "*";
		}

		return excluded ? "!" + rule : rule;
	}

	private boolean matchesRule(String rule, String serverName) {
		return rule.equals("*") || rule.equals("all") || rule.equals(serverName);
	}
}
