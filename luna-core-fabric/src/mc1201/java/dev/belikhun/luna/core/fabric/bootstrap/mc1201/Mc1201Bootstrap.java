package dev.belikhun.luna.core.fabric.bootstrap.mc1201;

import dev.belikhun.luna.core.fabric.LunaCoreFabricRuntime;
import dev.belikhun.luna.core.fabric.adapter.QueuedPlatformMessagingBridge;
import dev.belikhun.luna.core.fabric.adapter.mc1201.Mc1201NetworkingAdapter;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Mc1201Bootstrap {

	private static final AtomicBoolean LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean(false);
	private static final AtomicReference<Object> SERVER_REF = new AtomicReference<>();

	private static final QueuedPlatformMessagingBridge BRIDGE = new QueuedPlatformMessagingBridge(
		() -> new FabricMessageSource("fabric-mc1201", null, "")
	);

	private Mc1201Bootstrap() {
	}

	public static QueuedPlatformMessagingBridge bridge() {
		return BRIDGE;
	}

	public static Object server() {
		return SERVER_REF.get();
	}

	public static void register(LunaCoreFabricRuntime runtime) {
		registerLifecycleHooks();
		Mc1201NetworkingAdapter adapter = new Mc1201NetworkingAdapter();
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
