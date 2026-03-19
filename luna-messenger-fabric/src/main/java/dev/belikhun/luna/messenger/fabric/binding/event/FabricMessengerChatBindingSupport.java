package dev.belikhun.luna.messenger.fabric.binding.event;

import dev.belikhun.luna.messenger.fabric.service.FabricMessengerCommandService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricMessengerChatBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private FabricMessengerChatBindingSupport() {
	}

	public static boolean register(FabricMessengerCommandService commandService) {
		if (commandService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		boolean registered = registerAllowChatMessage(commandService) || registerChatMessage(commandService);
		if (!registered) {
			REGISTERED.set(false);
		}
		return registered;
	}

	private static boolean registerAllowChatMessage(FabricMessengerCommandService commandService) {
		return registerServerMessageEvent("ALLOW_CHAT_MESSAGE", commandService, true);
	}

	private static boolean registerChatMessage(FabricMessengerCommandService commandService) {
		return registerServerMessageEvent("CHAT_MESSAGE", commandService, false);
	}

	private static boolean registerServerMessageEvent(String fieldName, FabricMessengerCommandService commandService, boolean cancellable) {
		try {
			Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.message.v1.ServerMessageEvents");
			Field field = eventsClass.getField(fieldName);
			Object event = field.get(null);
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
				(proxy, invokedMethod, args) -> handleCallback(args, commandService, cancellable, invokedMethod.getReturnType())
			);
			registerMethod.invoke(event, callback);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static Method findRegisterMethod(Class<?> eventClass) {
		for (Method method : eventClass.getMethods()) {
			if (!method.getName().equals("register") || method.getParameterCount() != 1) {
				continue;
			}
			return method;
		}
		return null;
	}

	private static Object handleCallback(
		Object[] args,
		FabricMessengerCommandService commandService,
		boolean cancellable,
		Class<?> returnType
	) {
		PlayerIdentity identity = resolveIdentity(args);
		String content = resolveMessage(args);

		boolean allowVanilla = true;
		if (identity != null && content != null && !content.isBlank()) {
			var result = commandService.sendChat(identity.id(), identity.name(), identity.server(), content);
			if (result.success()) {
				allowVanilla = !cancellable;
			}
		}

		if (returnType == boolean.class || returnType == Boolean.class) {
			return allowVanilla;
		}
		return null;
	}

	private static PlayerIdentity resolveIdentity(Object[] args) {
		if (args == null) {
			return null;
		}

		for (Object arg : args) {
			if (arg == null) {
				continue;
			}

			UUID uuid = extractUuid(arg);
			if (uuid == null) {
				continue;
			}

			String name = extractName(arg);
			if (name == null || name.isBlank()) {
				name = "unknown";
			}
			return new PlayerIdentity(uuid, name, "fabric");
		}

		return null;
	}

	private static String resolveMessage(Object[] args) {
		if (args == null) {
			return "";
		}

		for (Object arg : args) {
			if (arg instanceof String value) {
				if (!value.isBlank()) {
					return value;
				}
				continue;
			}
			if (arg == null) {
				continue;
			}

			String extracted = invokeStringMethod(arg, "getSignedContent");
			if (extracted != null && !extracted.isBlank()) {
				return extracted;
			}

			extracted = invokeStringMethod(arg, "getString");
			if (extracted != null && !extracted.isBlank()) {
				return extracted;
			}
		}

		return "";
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

		return null;
	}

	private static String extractName(Object source) {
		Object name = invokeNoArgMethod(source, "getName");
		if (name == null) {
			return null;
		}
		if (name instanceof String value) {
			return value;
		}

		String literal = invokeStringMethod(name, "getString");
		if (literal != null && !literal.isBlank()) {
			return literal;
		}

		String value = name.toString();
		if ("null".equals(value)) {
			return null;
		}
		return value;
	}

	private static String invokeStringMethod(Object target, String methodName) {
		Object value = invokeNoArgMethod(target, methodName);
		if (value instanceof String string) {
			return string;
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

	private record PlayerIdentity(UUID id, String name, String server) {
	}
}
