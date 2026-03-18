package dev.belikhun.luna.core.fabric.adapter.mc119x;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.AbstractFabricFamilyNetworkingAdapter;

public final class Mc119xNetworkingAdapter extends AbstractFabricFamilyNetworkingAdapter {

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC119X;
	}

	@Override
	public String adapterId() {
		return "fabric-networking-mc119x";
	}

	@Override
	public void initialize() {
		// TODO: Wire custom-payload registration for 1.19.x family.
	}

	@Override
	public void shutdown() {
		// TODO: Unregister/cleanup networking resources for 1.19.x family.
	}
}
