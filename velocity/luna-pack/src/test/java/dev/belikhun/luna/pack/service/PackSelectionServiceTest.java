package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.pack.config.PackDefinition;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackFormat;
import dev.belikhun.luna.pack.model.PackFormatRange;
import dev.belikhun.luna.pack.model.PackReloadReport;
import dev.belikhun.luna.pack.model.ResolvedPack;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackSelectionServiceTest {
	private final PackSelectionService service = new PackSelectionService();

	@Test
	void withholdsPacksOutsideTheClientFormat() {
		PackCatalogSnapshot snapshot = snapshot(
			resolved("legacy", range(15, 64)),
			resolved("modern", range(69, 75)),
			resolved("undeclared", null)
		);

		List<String> forOldClient = names(service.selectForServer(snapshot, "survival", new PackFormat(34, 0)));
		List<String> forNewClient = names(service.selectForServer(snapshot, "survival", new PackFormat(75, 0)));

		assertEquals(List.of("legacy", "undeclared"), forOldClient);
		assertEquals(List.of("modern", "undeclared"), forNewClient);
	}

	@Test
	void nullClientFormatDisablesTheFilter() {
		PackCatalogSnapshot snapshot = snapshot(
			resolved("legacy", range(15, 64)),
			resolved("modern", range(69, 75))
		);

		List<String> selected = names(service.selectForServer(snapshot, "survival", null));

		assertEquals(List.of("legacy", "modern"), selected);
	}

	private PackFormatRange range(int min, int max) {
		return new PackFormatRange(new PackFormat(min, 0), new PackFormat(max, 0), "test", false);
	}

	private ResolvedPack resolved(String name, PackFormatRange range) {
		PackDefinition definition = new PackDefinition(
			name,
			name + ".zip",
			0,
			false,
			true,
			List.of("*"),
			Path.of(name + ".yml")
		);

		return new ResolvedPack(definition, URI.create("https://example.invalid/" + name), "", 1L, true, "", range);
	}

	private PackCatalogSnapshot snapshot(ResolvedPack... packs) {
		Map<String, PackDefinition> definitions = new LinkedHashMap<>();
		for (ResolvedPack pack : packs) {
			definitions.put(pack.definition().normalizedName(), pack.definition());
		}

		return PackCatalogSnapshot.from(definitions, List.of(packs), PackReloadReport.empty());
	}

	private List<String> names(List<ResolvedPack> packs) {
		return packs.stream().map(pack -> pack.definition().name()).toList();
	}
}
