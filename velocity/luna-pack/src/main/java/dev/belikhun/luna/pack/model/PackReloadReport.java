package dev.belikhun.luna.pack.model;

public record PackReloadReport(
	int discoveredFiles,
	int validDefinitions,
	int invalidDefinitions,
	int resolvedAvailable,
	int resolvedMissingFiles,
	int resolvedInvalidUrls
) {
	public static PackReloadReport empty() {
		return new PackReloadReport(0, 0, 0, 0, 0, 0);
	}
}
