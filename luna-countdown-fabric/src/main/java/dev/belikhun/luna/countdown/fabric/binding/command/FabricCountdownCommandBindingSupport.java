package dev.belikhun.luna.countdown.fabric.binding.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.countdown.fabric.service.FabricCountdownCommandService;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class FabricCountdownCommandBindingSupport {

	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
	private static final List<String> DURATION_PRESETS = List.of("10s", "30s", "1m", "5m", "10m", "30m", "1h");
	private static final List<String> SHUTDOWN_REASON_PRESETS = List.of("Bảo_trì", "Khởi_động_lại", "Cập_nhật_hệ_thống");

	private FabricCountdownCommandBindingSupport() {
	}

	public static boolean register(FabricCountdownCommandService commandService) {
		if (commandService == null) {
			return false;
		}
		if (!REGISTERED.compareAndSet(false, true)) {
			return true;
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher, commandService));
		return true;
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, FabricCountdownCommandService commandService) {
		dispatcher.register(createRootCommand("countdown", commandService));
		dispatcher.register(createRootCommand("cd", commandService));
		dispatcher.register(createShutdownCommand(commandService));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createRootCommand(String root, FabricCountdownCommandService commandService) {
		RequiredArgumentBuilder<CommandSourceStack, String> startLength = argument("length", StringArgumentType.word())
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

		RequiredArgumentBuilder<CommandSourceStack, Integer> stopId = argument("id", IntegerArgumentType.integer(1))
			.suggests((context, builder) -> suggestActiveCountdownIds(builder, commandService))
			.executes(context -> {
				var result = commandService.stop(IntegerArgumentType.getInteger(context, "id"));
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			});

		return literal(root)
			.requires(Permissions.require("countdown.countdown", 2))
			.then(literal("start").then(startLength))
			.then(literal("stop").then(stopId))
			.then(literal("stopall").executes(context -> {
				var result = commandService.stopAll();
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			}));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> createShutdownCommand(FabricCountdownCommandService commandService) {
		RequiredArgumentBuilder<CommandSourceStack, String> shutdownLength = argument("length", StringArgumentType.word())
			.suggests(FabricCountdownCommandBindingSupport::suggestDurationPresets)
			.executes(context -> {
				var result = commandService.scheduleShutdown(StringArgumentType.getString(context, "length"), null);
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			})
			.then(argument("message", StringArgumentType.greedyString())
				.suggests(FabricCountdownCommandBindingSupport::suggestShutdownReasons)
				.executes(context -> {
					var result = commandService.scheduleShutdown(
						StringArgumentType.getString(context, "length"),
						StringArgumentType.getString(context, "message")
					);
					sendFeedback(context.getSource(), result.success(), result.message());
					return result.success() ? 1 : 0;
				}));

		return literal("shutdown")
			.requires(Permissions.require("countdown.shutdown", 4))
			.then(literal("cancel").executes(context -> {
				var result = commandService.cancelShutdown();
				sendFeedback(context.getSource(), result.success(), result.message());
				return result.success() ? 1 : 0;
			}))
			.then(shutdownLength);
	}

	private static CompletableFuture<Suggestions> suggestDurationPresets(
		CommandContext<CommandSourceStack> context,
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

	private static CompletableFuture<Suggestions> suggestShutdownReasons(
		CommandContext<CommandSourceStack> context,
		SuggestionsBuilder builder
	) {
		String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (String preset : SHUTDOWN_REASON_PRESETS) {
			if (preset.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				builder.suggest(preset);
			}
		}
		return builder.buildFuture();
	}

	private static void sendFeedback(CommandSourceStack source, boolean success, String message) {
		Component text = Component.literal((success ? "§a✔ " : "§c❌ ") + message);
		if (success) {
			source.sendSuccess(() -> text, false);
			return;
		}

		source.sendFailure(text);
	}
}
