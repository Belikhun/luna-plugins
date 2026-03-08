package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.config.LoaderConfig;
import dev.belikhun.luna.pack.config.PackDefinition;
import dev.belikhun.luna.pack.config.PackRepository;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;
import dev.belikhun.luna.pack.model.ResolvedPack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class PackCatalogService {
	private final LunaLogger logger;
	private final PackRepository repository;
	private final PackHashService hashService;
	private final AtomicReference<PackCatalogSnapshot> snapshot;

	public PackCatalogService(Path dataDirectory, LunaLogger logger) {
		this.logger = logger.scope("Catalog");
		this.repository = new PackRepository(dataDirectory, this.logger);
		this.hashService = new PackHashService(this.logger);
		this.snapshot = new AtomicReference<>(PackCatalogSnapshot.empty());
	}

	public PackReloadReport reload(LoaderConfig config) {
		PackRepository.LoadResult loadResult = repository.load();
		Map<String, PackDefinition> definitions = loadResult.definitions();
		List<ResolvedPack> resolved = hashService.resolveAll(config, definitions.values());

		int available = 0;
		int missing = 0;
		int invalidUrls = 0;
		for (ResolvedPack value : resolved) {
			if (value.available()) {
				available++;
				continue;
			}
			if ("MISSING_FILE".equals(value.unavailableReason())) {
				missing++;
			}
			if ("INVALID_URL".equals(value.unavailableReason())) {
				invalidUrls++;
			}
		}

		PackReloadReport report = new PackReloadReport(
			loadResult.discoveredFiles(),
			definitions.size(),
			loadResult.invalidFiles().size(),
			available,
			missing,
			invalidUrls
		);

		snapshot.set(PackCatalogSnapshot.from(definitions, resolved, report));
		logger.audit("Đã nạp " + report.validDefinitions() + " pack hợp lệ từ " + report.discoveredFiles() + " file.");
		return report;
	}

	public PackCatalogSnapshot snapshot() {
		return snapshot.get();
	}
}
