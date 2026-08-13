package dev.belikhun.luna.legacy.messenger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaceholderResolutionResult {
	private final String resolvedContent;
	private final Map<String, String> exportedValues;

	public PlaceholderResolutionResult(String resolvedContent, Map<String, String> exportedValues) {
		resolvedContent = resolvedContent == null ? "" : resolvedContent;
		exportedValues = exportedValues == null
			? Collections.<String, String>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<String, String>(exportedValues));

		this.resolvedContent = resolvedContent;
		this.exportedValues = exportedValues;
	}

	public String resolvedContent() {
		return resolvedContent;
	}

	public Map<String, String> exportedValues() {
		return exportedValues;
	}

}
