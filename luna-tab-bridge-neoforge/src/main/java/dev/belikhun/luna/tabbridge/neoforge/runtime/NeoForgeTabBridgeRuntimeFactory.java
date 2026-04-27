package dev.belikhun.luna.tabbridge.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.Comparator;
import java.util.ServiceLoader;

public final class NeoForgeTabBridgeRuntimeFactory {
	private NeoForgeTabBridgeRuntimeFactory() {
	}

	public static NeoForgeTabBridgeRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		NeoForgeTabBridgeRuntimeProvider provider = ServiceLoader.load(NeoForgeTabBridgeRuntimeProvider.class).stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(NeoForgeTabBridgeRuntimeProvider::priority).reversed())
			.findFirst()
			.orElse(null);

		if (provider == null) {
			logger.warn("Không tìm thấy TAB bridge runtime provider cho NeoForge. Dùng runtime no-op.");
			return new NoopNeoForgeTabBridgeRuntime();
		}

		try {
			NeoForgeTabBridgeRuntime runtime = provider.createRuntime(logger.scope("Runtime/" + provider.name()), dependencyManager);
			if (runtime == null) {
				return new NoopNeoForgeTabBridgeRuntime();
			}

			logger.audit("Đã chọn TAB bridge runtime provider: " + provider.name());
			return runtime;
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo TAB bridge runtime provider. Dùng runtime no-op.", exception);
			return new NoopNeoForgeTabBridgeRuntime();
		}
	}
}
