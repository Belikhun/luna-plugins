package dev.belikhun.luna.pack.model;

import dev.belikhun.luna.pack.config.PackDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record PackCatalogSnapshot(
	Map<String, PackDefinition> definitionsByName,
	Map<String, ResolvedPack> resolvedByName,
	PackReloadReport report
) {
	public static PackCatalogSnapshot empty() {
		return new PackCatalogSnapshot(Map.of(), Map.of(), PackReloadReport.empty());
	}

	public Collection<ResolvedPack> resolvedPacks() {
		return resolvedByName.values();
	}

	public ResolvedPack findResolved(String packName) {
		if (packName == null) {
			return null;
		}
		return resolvedByName.get(packName.toLowerCase(Locale.ROOT));
	}

	public static PackCatalogSnapshot from(Map<String, PackDefinition> definitions, Collection<ResolvedPack> resolved, PackReloadReport report) {
		Map<String, ResolvedPack> resolvedMap = new LinkedHashMap<>();
		for (ResolvedPack value : resolved) {
			resolvedMap.put(value.definition().normalizedName(), value);
		}
		return new PackCatalogSnapshot(
			Collections.unmodifiableMap(new LinkedHashMap<>(definitions)),
			Collections.unmodifiableMap(resolvedMap),
			report
		);
	}
}
