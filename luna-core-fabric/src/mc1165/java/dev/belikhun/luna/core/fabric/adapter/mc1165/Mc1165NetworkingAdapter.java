package dev.belikhun.luna.core.fabric.adapter.mc1165;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.AbstractFabricFamilyNetworkingAdapter;

public final class Mc1165NetworkingAdapter extends AbstractFabricFamilyNetworkingAdapter {

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC1165;
	}

	@Override
	public String adapterId() {
		return "fabric-networking-mc1165";
	}

	@Override
	public void initialize() {
		// TODO: Wire legacy custom-payload registration for 1.16.5 family.
	}

	@Override
	public void shutdown() {
		// TODO: Unregister/cleanup networking resources for 1.16.5 family.
	}
}
