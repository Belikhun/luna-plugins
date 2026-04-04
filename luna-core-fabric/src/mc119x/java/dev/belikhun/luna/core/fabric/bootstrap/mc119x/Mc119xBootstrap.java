package dev.belikhun.luna.core.fabric.bootstrap.mc119x;

import dev.belikhun.luna.core.fabric.FabricFamilyBootstrap;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.mc119x.Mc119xNetworkingAdapter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Mc119xBootstrap implements FabricFamilyBootstrap {
	private static final AtomicBoolean LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean(false);
	private static final AtomicReference<MinecraftServer> SERVER_REF = new AtomicReference<>();

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC119X;
	}

	@Override
	public void register(LunaCoreFabricRuntime runtime) {
		registerLifecycleHooks();
		runtime.registerNetworkingAdapter(new Mc119xNetworkingAdapter());
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
