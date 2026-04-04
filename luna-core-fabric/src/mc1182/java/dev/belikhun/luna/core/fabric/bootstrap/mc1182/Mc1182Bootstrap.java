package dev.belikhun.luna.core.fabric.bootstrap.mc1182;

import dev.belikhun.luna.core.fabric.FabricFamilyBootstrap;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.mc1182.Mc1182NetworkingAdapter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Mc1182Bootstrap implements FabricFamilyBootstrap {
	private static final AtomicBoolean LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean(false);
	private static final AtomicReference<MinecraftServer> SERVER_REF = new AtomicReference<>();

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC1182;
	}

	@Override
	public void register(LunaCoreFabricRuntime runtime) {
		registerLifecycleHooks();
		runtime.registerNetworkingAdapter(new Mc1182NetworkingAdapter());
	}

	@Override
	public MinecraftServer server() {
		return SERVER_REF.get();
	}

	private static void registerLifecycleHooks() {
		if (!LIFECYCLE_HOOKS_REGISTERED.compareAndSet(false, true)) {
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(SERVER_REF::set);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVER_REF.compareAndSet(server, null));
	}
}
