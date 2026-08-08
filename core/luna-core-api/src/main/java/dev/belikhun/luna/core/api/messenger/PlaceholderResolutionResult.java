package dev.belikhun.luna.core.api.messenger;

import java.util.Map;

public record PlaceholderResolutionResult(
	String resolvedContent,
	Map<String, String> exportedValues
) {
	public PlaceholderResolutionResult {
		resolvedContent = resolvedContent == null ? "" : resolvedContent;
		exportedValues = exportedValues == null ? Map.of() : Map.copyOf(exportedValues);
	}
}
