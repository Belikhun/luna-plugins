package dev.belikhun.luna.core.fabric.adapter.mc1182;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.AbstractFabricFamilyNetworkingAdapter;

public final class Mc1182NetworkingAdapter extends AbstractFabricFamilyNetworkingAdapter {

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC1182;
	}

	@Override
	public String adapterId() {
		return "fabric-networking-mc1182";
	}

	@Override
	public void initialize() {
		// TODO: Wire custom-payload registration for 1.18.2 family.
	}

	@Override
	public void shutdown() {
		// TODO: Unregister/cleanup networking resources for 1.18.2 family.
	}
}
