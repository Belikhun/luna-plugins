package dev.belikhun.luna.messenger.fabric.binding.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.messenger.fabric.service.FabricMessengerCommandService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

public final class FabricMessengerCommandBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private FabricMessengerCommandBindingSupport() {
	}

	public static boolean register(FabricMessengerCommandService commandService) {
		if (commandService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		if (!registerCommandCallback(commandService)) {
			REGISTERED.set(false);
			return false;
		}
		return true;
	}

	private static boolean registerCommandCallback(FabricMessengerCommandService commandService) {
		try {
			Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
			Field eventField = callbackClass.getField("EVENT");
			Object event = eventField.get(null);
			if (event == null) {
				return false;
			}

			Method registerMethod = findSingleArgumentMethod(event.getClass(), "register");
			if (registerMethod == null) {
				return false;
			}

			Class<?> callbackType = registerMethod.getParameterTypes()[0];
			Object callback = Proxy.newProxyInstance(
				callbackType.getClassLoader(),
				new Class<?>[] { callbackType },
				(proxy, invokedMethod, args) -> {
					if (args != null && args.length > 0 && args[0] instanceof CommandDispatcher<?> dispatcher) {
						registerCommands(dispatcher, commandService);
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

	private static Method findSingleArgumentMethod(Class<?> owner, String methodName) {
		for (Method method : owner.getMethods()) {
			if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
				return method;
			}
		}
		return null;
	}

	private static void registerCommands(CommandDispatcher<?> dispatcher, FabricMessengerCommandService commandService) {
		@SuppressWarnings("unchecked")
		CommandDispatcher<Object> typedDispatcher = (CommandDispatcher<Object>) dispatcher;

		typedDispatcher.register(literal("nw").executes(context -> {
			PlayerIdentity identity = resolveIdentity(context.getSource());
			if (identity == null) {
				sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
				return 0;
			}
			var result = commandService.switchNetwork(identity.id(), identity.name(), identity.server());
			sendFeedback(context.getSource(), result.success(), result.message());
			return result.success() ? 1 : 0;
		}));

		typedDispatcher.register(literal("sv").executes(context -> {
			PlayerIdentity identity = resolveIdentity(context.getSource());
			if (identity == null) {
				sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
				return 0;
			}
			var result = commandService.switchServer(identity.id(), identity.name(), identity.server());
			sendFeedback(context.getSource(), result.success(), result.message());
			return result.success() ? 1 : 0;
		}));

		RequiredArgumentBuilder<Object, String> msgTarget = argument("target", StringArgumentType.word())
			.suggests((context, builder) -> suggestTargets(context.getSource(), builder, commandService))
			.then(argument("message", StringArgumentType.greedyString())
				.executes(context -> {
					PlayerIdentity identity = resolveIdentity(context.getSource());
					if (identity == null) {
						sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
						return 0;
					}
					var result = commandService.sendDirect(
						identity.id(),
						identity.name(),
						identity.server(),
						StringArgumentType.getString(context, "target"),
						StringArgumentType.getString(context, "message")
					);
					sendFeedback(context.getSource(), result.success(), result.message());
					return result.success() ? 1 : 0;
				}));
		typedDispatcher.register(literal("msg").then(msgTarget));

		typedDispatcher.register(literal("r")
			.then(argument("message", StringArgumentType.greedyString())
				.executes(context -> {
					PlayerIdentity identity = resolveIdentity(context.getSource());
					if (identity == null) {
						sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
						return 0;
					}
					var result = commandService.sendReply(
						identity.id(),
						identity.name(),
						identity.server(),
						StringArgumentType.getString(context, "message")
					);
					sendFeedback(context.getSource(), result.success(), result.message());
					return result.success() ? 1 : 0;
				})));

		typedDispatcher.register(literal("poke")
			.then(argument("target", StringArgumentType.word())
				.suggests((context, builder) -> suggestTargets(context.getSource(), builder, commandService))
				.executes(context -> {
					PlayerIdentity identity = resolveIdentity(context.getSource());
					if (identity == null) {
						sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
						return 0;
					}
					var result = commandService.sendPoke(
						identity.id(),
						identity.name(),
						identity.server(),
						StringArgumentType.getString(context, "target")
					);
					sendFeedback(context.getSource(), result.success(), result.message());
					return result.success() ? 1 : 0;
				})));
	}

	private static CompletableFuture<Suggestions> suggestTargets(
		Object source,
		SuggestionsBuilder builder,
		FabricMessengerCommandService commandService
	) {
		String senderName = "";
		PlayerIdentity identity = resolveIdentity(source);
		if (identity != null) {
			senderName = identity.name();
		}

		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (String candidate : commandService.suggestDirectTargets(builder.getRemaining(), senderName)) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			if (!candidate.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				continue;
			}
			builder.suggest(candidate);
		}

		return builder.buildFuture();
	}

	private static PlayerIdentity resolveIdentity(Object source) {
		try {
			Object player = invokeIfPresent(source, "getPlayerOrException");
			if (player == null) {
				player = invokeIfPresent(source, "getPlayer");
			}
			if (player == null) {
				return null;
			}

			UUID uuid = null;
			Object uuidValue = invokeIfPresent(player, "getUuid");
			if (uuidValue instanceof UUID value) {
				uuid = value;
			}
			if (uuid == null) {
				uuidValue = invokeIfPresent(player, "getUUID");
				if (uuidValue instanceof UUID value) {
					uuid = value;
				}
			}
			if (uuid == null) {
				return null;
			}

			String name = String.valueOf(invokeIfPresent(player, "getName"));
			if (name == null || name.isBlank() || "null".equals(name)) {
				name = "unknown";
			}
			return new PlayerIdentity(uuid, name, "fabric");
		} catch (Exception exception) {
			return null;
		}
	}

	private static Object invokeIfPresent(Object target, String methodName) {
		if (target == null) {
			return null;
		}
		for (Method method : target.getClass().getMethods()) {
			if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
				try {
					return method.invoke(target);
				} catch (ReflectiveOperationException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private static void sendFeedback(Object source, boolean success, String message) {
		Object text = createText((success ? "§a✔ " : "§c❌ ") + message);
		if (text == null) {
			return;
		}

		if (!success && invokeIfPresent(source, "sendError", text)) {
			return;
		}
		if (invokeIfPresent(source, "sendSystemMessage", text)) {
			return;
		}
		if (invokeIfPresent(source, "sendMessage", text)) {
			return;
		}

		trySendFeedbackSupplier(source, text, success);
	}

	private static Object createText(String message) {
		try {
			Class<?> textClass = Class.forName("net.minecraft.network.chat.Component");
			Method literal = textClass.getMethod("literal", String.class);
			return literal.invoke(null, message);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private static void trySendFeedbackSupplier(Object source, Object text, boolean broadcastToOps) {
		for (Method method : source.getClass().getMethods()) {
			if (!method.getName().equals("sendSuccess") || method.getParameterCount() != 2) {
				continue;
			}
			Class<?> supplierType = method.getParameterTypes()[0];
			Class<?> flagType = method.getParameterTypes()[1];
			if (!Supplier.class.isAssignableFrom(supplierType) || !(flagType == boolean.class || flagType == Boolean.class)) {
				continue;
			}

			try {
				Object supplier = Proxy.newProxyInstance(
					supplierType.getClassLoader(),
					new Class<?>[] { supplierType },
					(proxy, invokedMethod, args) -> {
						if (invokedMethod.getName().equals("get")) {
							return text;
						}
						return null;
					}
				);
				method.invoke(source, supplier, broadcastToOps);
				return;
			} catch (ReflectiveOperationException ignored) {
				return;
			}
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, Object arg) {
		if (target == null || arg == null) {
			return false;
		}

		for (Method method : target.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}
			if (!method.getParameterTypes()[0].isAssignableFrom(arg.getClass())) {
				continue;
			}

			try {
				method.invoke(target, arg);
				return true;
			} catch (ReflectiveOperationException ignored) {
				return false;
			}
		}

		return false;
	}

	private record PlayerIdentity(UUID id, String name, String server) {
	}
}
