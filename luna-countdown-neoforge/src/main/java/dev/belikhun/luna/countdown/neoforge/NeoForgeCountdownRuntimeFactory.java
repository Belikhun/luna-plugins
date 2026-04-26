package dev.belikhun.luna.countdown.neoforge;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.Comparator;
import java.util.ServiceLoader;

public final class NeoForgeCountdownRuntimeFactory {
	private NeoForgeCountdownRuntimeFactory() {
	}

	public static NeoForgeCountdownRuntime create(LunaLogger logger) {
		NeoForgeCountdownRuntimeProvider provider = ServiceLoader.load(NeoForgeCountdownRuntimeProvider.class).stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(NeoForgeCountdownRuntimeProvider::priority).reversed())
			.findFirst()
			.orElse(null);

		if (provider == null) {
			logger.warn("Không tìm thấy countdown runtime provider cho NeoForge. Dùng runtime no-op.");
			return new DefaultNeoForgeCountdownRuntime(new NoopNeoForgeCountdownScheduler(), new NoopNeoForgeCountdownNotifier(), logger.scope("Runtime/Noop"));
		}

		try {
			NeoForgeCountdownScheduler scheduler = provider.createScheduler(logger.scope("Runtime/" + provider.name()));
			NeoForgeCountdownNotifier notifier = provider.createNotifier(logger.scope("Runtime/" + provider.name()));
			if (scheduler == null) {
				scheduler = new NoopNeoForgeCountdownScheduler();
			}
			if (notifier == null) {
				notifier = new NoopNeoForgeCountdownNotifier();
			}
			logger.audit("Đã chọn countdown runtime provider: " + provider.name());
			return new DefaultNeoForgeCountdownRuntime(scheduler, notifier, logger.scope("Runtime/" + provider.name()));
		} catch (RuntimeException exception) {
			logger.error("Không thể khởi tạo countdown runtime provider. Dùng runtime no-op.", exception);
			return new DefaultNeoForgeCountdownRuntime(new NoopNeoForgeCountdownScheduler(), new NoopNeoForgeCountdownNotifier(), logger.scope("Runtime/Noop"));
		}
	}
}
