package dev.belikhun.luna.core.fabric.adapter;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class FabricFamilyAdapterRegistry {

	private final Map<FabricVersionFamily, FabricFamilyNetworkingAdapter> adapters = new EnumMap<>(FabricVersionFamily.class);

	public void register(FabricFamilyNetworkingAdapter adapter) {
		adapters.put(adapter.family(), adapter);
	}

	public Optional<FabricFamilyNetworkingAdapter> get(FabricVersionFamily family) {
		return Optional.ofNullable(adapters.get(family));
	}

	public Map<FabricVersionFamily, FabricFamilyNetworkingAdapter> snapshot() {
		return Map.copyOf(adapters);
	}
}
