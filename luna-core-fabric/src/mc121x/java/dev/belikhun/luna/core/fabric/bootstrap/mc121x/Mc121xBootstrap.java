package dev.belikhun.luna.core.fabric.bootstrap.mc121x;

import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.QueuedPlatformMessagingBridge;
import dev.belikhun.luna.core.fabric.adapter.mc121x.Mc121xNetworkingAdapter;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Mc121xBootstrap {
	private static final AtomicBoolean LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean(false);
	private static final AtomicReference<Object> SERVER_REF = new AtomicReference<>();

	private static final QueuedPlatformMessagingBridge BRIDGE = new QueuedPlatformMessagingBridge(
		() -> new FabricMessageSource("fabric-mc121x", null, "")
	);

	private Mc121xBootstrap() {
	}

	public static QueuedPlatformMessagingBridge bridge() {
		return BRIDGE;
	}

	public static Object server() {
		return SERVER_REF.get();
	}

	public static void register(LunaCoreFabricRuntime runtime) {
		registerLifecycleHooks();
		Mc121xNetworkingAdapter adapter = new Mc121xNetworkingAdapter();
		adapter.bindPlatformBridge(BRIDGE);
		runtime.registerNetworkingAdapter(adapter);
	}

	private static void registerLifecycleHooks() {
		if (!LIFECYCLE_HOOKS_REGISTERED.compareAndSet(false, true)) {
			return;
		}

		try {
			Class<?> lifecycleClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
			Object serverStartedEvent = lifecycleClass.getField("SERVER_STARTED").get(null);
			Object serverStoppedEvent = lifecycleClass.getField("SERVER_STOPPED").get(null);

			java.lang.reflect.Method startedRegister = findSingleArgMethod(serverStartedEvent.getClass(), "register");
			java.lang.reflect.Method stoppedRegister = findSingleArgMethod(serverStoppedEvent.getClass(), "register");
			if (startedRegister == null || stoppedRegister == null) {
				return;
			}

			startedRegister.invoke(serverStartedEvent, (java.util.function.Consumer<Object>) SERVER_REF::set);
			stoppedRegister.invoke(serverStoppedEvent, (java.util.function.Consumer<Object>) server -> SERVER_REF.compareAndSet(server, null));
		} catch (ReflectiveOperationException ignored) {
			SERVER_REF.set(null);
		}
	}

	private static java.lang.reflect.Method findSingleArgMethod(Class<?> owner, String name) {
		for (java.lang.reflect.Method method : owner.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 1) {
				return method;
			}
		}
		return null;
	}
}
