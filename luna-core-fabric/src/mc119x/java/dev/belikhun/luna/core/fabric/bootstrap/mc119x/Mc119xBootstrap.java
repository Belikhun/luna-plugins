package dev.belikhun.luna.core.fabric.bootstrap.mc119x;

import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.mc119x.Mc119xNetworkingAdapter;

public final class Mc119xBootstrap {

	private Mc119xBootstrap() {
	}

	public static void register(LunaCoreFabricRuntime runtime) {
		runtime.registerNetworkingAdapter(new Mc119xNetworkingAdapter());
	}
}
