package dev.belikhun.luna.messenger.neoforge.runtime;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.BackendPlaceholderResolver;

import java.util.Comparator;
import java.util.ServiceLoader;

public final class NeoForgeMessengerRuntimeFactory {
	private NeoForgeMessengerRuntimeFactory() {
	}

	public static NeoForgeMessengerRuntime create(LunaLogger logger, DependencyManager dependencyManager) {
		NeoForgeMessengerRuntimeProvider provider = ServiceLoader.load(NeoForgeMessengerRuntimeProvider.class).stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(NeoForgeMessengerRuntimeProvider::priority).reversed())
			.findFirst()
			.orElse(null);

		if (provider == null) {
			logger.warn("Không tìm thấy messenger runtime provider cho NeoForge. Dùng runtime no-op.");
			return new NoopNeoForgeMessengerRuntime(new NoopBackendPlaceholderResolver());
		}

		try {
			BackendPlaceholderResolver placeholderResolver = provider.createPlaceholderResolver(logger.scope("Runtime/" + provider.name()), dependencyManager);
			if (placeholderResolver == null) {
				placeholderResolver = new NoopBackendPlaceholderResolver();
			}

			NeoForgeMessengerRuntime runtime = provider.createRuntime(logger.scope("Runtime/" + provider.name()), dependencyManager, placeholderResolver);
			if (runtime == null) {
				return new NoopNeoForgeMessengerRuntime(placeholderResolver);
			}

			logger.audit("Đã chọn messenger runtime provider: " + provider.name());
			return runtime;
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo messenger runtime provider. Dùng runtime no-op.", exception);
			return new NoopNeoForgeMessengerRuntime(new NoopBackendPlaceholderResolver());
		}
	}
}
