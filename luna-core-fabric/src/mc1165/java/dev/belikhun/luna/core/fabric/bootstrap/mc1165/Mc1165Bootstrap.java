package dev.belikhun.luna.core.fabric.bootstrap.mc1165;

import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.mc1165.Mc1165NetworkingAdapter;

public final class Mc1165Bootstrap {

	private Mc1165Bootstrap() {
	}

	public static void register(LunaCoreFabricRuntime runtime) {
		runtime.registerNetworkingAdapter(new Mc1165NetworkingAdapter());
	}
}
