package dev.belikhun.luna.countdown.fabric.binding.event;

import dev.belikhun.luna.countdown.fabric.service.FabricCountdownService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricCountdownPlayerEventBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private FabricCountdownPlayerEventBindingSupport() {
	}

	public static boolean register(FabricCountdownService countdownService) {
		if (countdownService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		boolean joinRegistered = registerConnectionEvent("JOIN", countdownService, true);
		boolean quitRegistered = registerConnectionEvent("DISCONNECT", countdownService, false);
		if (!(joinRegistered && quitRegistered)) {
			REGISTERED.set(false);
			return false;
		}

		return true;
	}

	private static boolean registerConnectionEvent(String fieldName, FabricCountdownService countdownService, boolean joinEvent) {
		try {
			Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
			Field eventField = eventsClass.getField(fieldName);
			Object event = eventField.get(null);
			if (event == null) {
				return false;
			}

			Method registerMethod = findRegisterMethod(event.getClass());
			if (registerMethod == null) {
				return false;
			}

			Class<?> callbackType = registerMethod.getParameterTypes()[0];
			Object callback = Proxy.newProxyInstance(
				callbackType.getClassLoader(),
				new Class<?>[] { callbackType },
				(proxy, method, args) -> {
					UUID playerId = resolvePlayerId(args);
					if (joinEvent) {
						countdownService.handlePlayerJoin(playerId);
					} else {
						countdownService.handlePlayerQuit(playerId);
					}
					return null;
				}
			);

			registerMethod.invoke(event, callback);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static Method findRegisterMethod(Class<?> eventClass) {
		for (Method method : eventClass.getMethods()) {
			if (method.getName().equals("register") && method.getParameterCount() == 1) {
				return method;
			}
		}
		return null;
	}

	private static UUID resolvePlayerId(Object[] args) {
		if (args == null) {
			return null;
		}

		for (Object arg : args) {
			if (arg == null) {
				continue;
			}

			UUID uuid = extractUuid(arg);
			if (uuid != null) {
				return uuid;
			}
		}

		return null;
	}

	private static UUID extractUuid(Object source) {
		Object uuid = invokeNoArgMethod(source, "getUuid");
		if (uuid instanceof UUID value) {
			return value;
		}

		uuid = invokeNoArgMethod(source, "getUUID");
		if (uuid instanceof UUID value) {
			return value;
		}

		Object player = invokeNoArgMethod(source, "getPlayer");
		if (player != null) {
			uuid = invokeNoArgMethod(player, "getUuid");
			if (uuid instanceof UUID value) {
				return value;
			}
			uuid = invokeNoArgMethod(player, "getUUID");
			if (uuid instanceof UUID value) {
				return value;
			}
		}

		return null;
	}

	private static Object invokeNoArgMethod(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		for (Method method : target.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 0) {
				continue;
			}

			try {
				return method.invoke(target);
			} catch (ReflectiveOperationException ignored) {
				return null;
			}
		}

		return null;
	}
}
