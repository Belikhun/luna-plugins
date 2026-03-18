package dev.belikhun.luna.pack.api;

import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;

public record LunaPackReloadEvent(
	PackCatalogSnapshot previousSnapshot,
	PackCatalogSnapshot currentSnapshot,
	PackReloadReport report,
	long atEpochMillis
) {
}
