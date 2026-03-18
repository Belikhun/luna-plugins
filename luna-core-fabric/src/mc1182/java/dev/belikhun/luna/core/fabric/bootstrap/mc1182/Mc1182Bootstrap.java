package dev.belikhun.luna.core.fabric.bootstrap.mc1182;

import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.mc1182.Mc1182NetworkingAdapter;

public final class Mc1182Bootstrap {

	private Mc1182Bootstrap() {
	}

	public static void register(LunaCoreFabricRuntime runtime) {
		runtime.registerNetworkingAdapter(new Mc1182NetworkingAdapter());
	}
}
