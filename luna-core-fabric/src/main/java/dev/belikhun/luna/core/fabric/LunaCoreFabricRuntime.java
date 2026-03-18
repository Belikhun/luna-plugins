package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyAdapterRegistry;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyNetworkingAdapter;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

public final class LunaCoreFabricRuntime {

	private final FabricFamilyAdapterRegistry adapterRegistry = new FabricFamilyAdapterRegistry();
	private final AtomicReference<FabricVersionFamily> activeFamily = new AtomicReference<>();

	public void registerNetworkingAdapter(FabricFamilyNetworkingAdapter adapter) {
		adapterRegistry.register(adapter);
	}

	public void start(FabricVersionFamily family) {
		ensureFamilyBootstrapLoaded(family);
		FabricFamilyNetworkingAdapter adapter = adapterRegistry.get(family)
			.orElseThrow(() -> new IllegalStateException("No networking adapter registered for family " + family.id()));
		activeFamily.set(family);
		adapter.initialize();
	}

	public void stop(FabricVersionFamily family) {
		adapterRegistry.get(family).ifPresent(FabricFamilyNetworkingAdapter::shutdown);
		activeFamily.compareAndSet(family, null);
	}

	public FabricPluginMessagingBus createPluginMessagingBus(LunaLogger logger, boolean loggingEnabled) {
		return new FabricPluginMessagingBus(
			adapterRegistry,
			() -> {
				FabricVersionFamily family = activeFamily.get();
				if (family == null) {
					throw new IllegalStateException("Fabric runtime is not started.");
				}
				return family;
			},
			logger,
			loggingEnabled
		);
	}

	public FabricFamilyAdapterRegistry adapterRegistry() {
		return adapterRegistry;
	}

	private void ensureFamilyBootstrapLoaded(FabricVersionFamily family) {
		if (adapterRegistry.get(family).isPresent()) {
			return;
		}

		String bootstrapClassName = switch (family) {
			case MC1165 -> "dev.belikhun.luna.core.fabric.bootstrap.mc1165.Mc1165Bootstrap";
			case MC1182 -> "dev.belikhun.luna.core.fabric.bootstrap.mc1182.Mc1182Bootstrap";
			case MC119X -> "dev.belikhun.luna.core.fabric.bootstrap.mc119x.Mc119xBootstrap";
			case MC1201 -> "dev.belikhun.luna.core.fabric.bootstrap.mc1201.Mc1201Bootstrap";
			case MC121X -> "dev.belikhun.luna.core.fabric.bootstrap.mc121x.Mc121xBootstrap";
		};

		try {
			Class<?> bootstrapClass = Class.forName(bootstrapClassName);
			Method registerMethod = bootstrapClass.getMethod("register", LunaCoreFabricRuntime.class);
			registerMethod.invoke(null, this);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Unable to load Fabric bootstrap for family " + family.id() + ": " + bootstrapClassName, exception);
		}
	}
}
