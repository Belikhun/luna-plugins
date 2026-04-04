package dev.belikhun.luna.core.fabric;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyAdapterRegistry;
import dev.belikhun.luna.core.fabric.adapter.FabricFamilyNetworkingAdapter;
import dev.belikhun.luna.core.fabric.messaging.FabricPluginMessagingBus;
import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

public final class LunaCoreFabricRuntime {

	private final FabricFamilyAdapterRegistry adapterRegistry = new FabricFamilyAdapterRegistry();
	private final Map<FabricVersionFamily, FabricFamilyBootstrap> bootstraps = loadBootstraps();
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

	public MinecraftServer currentServer() {
		FabricVersionFamily family = activeFamily.get();
		if (family == null) {
			return null;
		}

		FabricFamilyBootstrap bootstrap = bootstraps.get(family);
		return bootstrap == null ? null : bootstrap.server();
	}

	private void ensureFamilyBootstrapLoaded(FabricVersionFamily family) {
		if (adapterRegistry.get(family).isPresent()) {
			return;
		}

		FabricFamilyBootstrap bootstrap = bootstraps.get(family);
		if (bootstrap == null) {
			throw new IllegalStateException("Unable to load Fabric bootstrap for family " + family.id());
		}

		bootstrap.register(this);
	}

	private Map<FabricVersionFamily, FabricFamilyBootstrap> loadBootstraps() {
		Map<FabricVersionFamily, FabricFamilyBootstrap> discovered = new EnumMap<>(FabricVersionFamily.class);
		for (FabricFamilyBootstrap bootstrap : ServiceLoader.load(FabricFamilyBootstrap.class, getClass().getClassLoader())) {
			discovered.put(bootstrap.family(), bootstrap);
		}
		return discovered;
	}
}
