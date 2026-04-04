package dev.belikhun.luna.core.fabric.bootstrap.mc121x;

import dev.belikhun.luna.core.fabric.FabricFamilyBootstrap;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.QueuedPlatformMessagingBridge;
import dev.belikhun.luna.core.fabric.adapter.mc121x.Mc121xNetworkingAdapter;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Mc121xBootstrap implements FabricFamilyBootstrap {
	private static final AtomicBoolean LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean(false);
	private static final AtomicReference<MinecraftServer> SERVER_REF = new AtomicReference<>();

	private static final QueuedPlatformMessagingBridge BRIDGE = new QueuedPlatformMessagingBridge(
		() -> new FabricMessageSource("fabric-mc121x", null, "")
	);

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC121X;
	}

	public static QueuedPlatformMessagingBridge bridge() {
		return BRIDGE;
	}

	public static MinecraftServer currentServer() {
		return SERVER_REF.get();
	}

	@Override
	public MinecraftServer server() {
		return currentServer();
	}

	@Override
	public void register(LunaCoreFabricRuntime runtime) {
		registerLifecycleHooks();
		Mc121xNetworkingAdapter adapter = new Mc121xNetworkingAdapter();
		adapter.bindPlatformBridge(BRIDGE);
		runtime.registerNetworkingAdapter(adapter);
	}

	private static void registerLifecycleHooks() {
		if (!LIFECYCLE_HOOKS_REGISTERED.compareAndSet(false, true)) {
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(SERVER_REF::set);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVER_REF.compareAndSet(server, null));
	}
}
