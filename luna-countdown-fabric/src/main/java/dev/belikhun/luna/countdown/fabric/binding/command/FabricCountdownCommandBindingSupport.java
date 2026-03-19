package dev.belikhun.luna.countdown.fabric.binding.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownCommandService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

public final class FabricCountdownCommandBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static final List<String> DURATION_PRESETS = List.of("10s", "30s", "1m", "5m", "10m", "30m", "1h");

	private FabricCountdownCommandBindingSupport() {
	}

	public static boolean register(FabricCountdownCommandService commandService) {
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

	private static boolean registerCommandCallback(FabricCountdownCommandService commandService) {
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

	private static void registerCommands(CommandDispatcher<?> dispatcher, FabricCountdownCommandService commandService) {
		@SuppressWarnings("unchecked")
		CommandDispatcher<Object> typedDispatcher = (CommandDispatcher<Object>) dispatcher;

		typedDispatcher.register(createRootCommand("countdown", commandService));
		typedDispatcher.register(createRootCommand("cd", commandService));
	}

	private static LiteralArgumentBuilder<Object> createRootCommand(String root, FabricCountdownCommandService commandService) {
		RequiredArgumentBuilder<Object, String> startLength = argument("length", StringArgumentType.word())
			.suggests(FabricCountdownCommandBindingSupport::suggestDurationPresets)
			.executes(context -> {
				var result = commandService.start(StringArgumentType.getString(context, "length"), null);
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			})
			.then(argument("title", StringArgumentType.greedyString())
				.executes(context -> {
					var result = commandService.start(
						StringArgumentType.getString(context, "length"),
						StringArgumentType.getString(context, "title")
					);
					sendFeedback(context.getSource(), result.success(), result.message());
					return result.success() ? 1 : 0;
				}));

		RequiredArgumentBuilder<Object, Integer> stopId = argument("id", IntegerArgumentType.integer(1))
			.suggests((context, builder) -> suggestActiveCountdownIds(builder, commandService))
			.executes(context -> {
				var result = commandService.stop(IntegerArgumentType.getInteger(context, "id"));
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			});

		return literal(root)
			.then(literal("start").then(startLength))
			.then(literal("stop").then(stopId))
			.then(literal("stopall").executes(context -> {
				var result = commandService.stopAll();
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			}));
	}

	private static CompletableFuture<Suggestions> suggestDurationPresets(
		com.mojang.brigadier.context.CommandContext<Object> context,
		SuggestionsBuilder builder
	) {
		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (String preset : DURATION_PRESETS) {
			if (preset.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(preset);
			}
		}
		return builder.buildFuture();
	}

	private static CompletableFuture<Suggestions> suggestActiveCountdownIds(
		SuggestionsBuilder builder,
		FabricCountdownCommandService commandService
	) {
		String remaining = builder.getRemaining();
		for (var snapshot : commandService.snapshots()) {
			String id = Integer.toString(snapshot.id());
			if (id.startsWith(remaining)) {
				builder.suggest(id);
			}
		}
		return builder.buildFuture();
	}

	private static void sendFeedback(Object source, boolean success, String message) {
		Object text = createText((success ? "§a✔ " : "§c❌ ") + message);
		if (text == null) {
			return;
		}

		if (invokeIfPresent(source, "sendError", text)) {
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
}
