package dev.belikhun.luna.messenger.fabric.binding.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.belikhun.luna.core.fabric.util.FabricPlayerNames;
import dev.belikhun.luna.messenger.fabric.service.FabricMessengerCommandService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher, commandService));
		return true;
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, FabricMessengerCommandService commandService) {
		dispatcher.register(literal("nw").executes(context -> {
			PlayerIdentity identity = resolveIdentity(context.getSource());
			if (identity == null) {
				sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
				return 0;
			}
			var result = commandService.switchNetwork(identity.id(), identity.name(), identity.server());
			sendFeedback(context.getSource(), result.success(), result.message());
			return result.success() ? 1 : 0;
		}));

		dispatcher.register(literal("sv").executes(context -> {
			PlayerIdentity identity = resolveIdentity(context.getSource());
			if (identity == null) {
				sendFeedback(context.getSource(), false, "Lệnh này chỉ dùng cho người chơi.");
				return 0;
			}
			var result = commandService.switchServer(identity.id(), identity.name(), identity.server());
			sendFeedback(context.getSource(), result.success(), result.message());
			return result.success() ? 1 : 0;
		}));

		RequiredArgumentBuilder<CommandSourceStack, String> msgTarget = argument("target", StringArgumentType.word())
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
		dispatcher.register(literal("msg").then(msgTarget));

		dispatcher.register(literal("r")
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

		dispatcher.register(literal("poke")
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
		CommandSourceStack source,
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

	private static PlayerIdentity resolveIdentity(CommandSourceStack source) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			String name = FabricPlayerNames.resolve(player);
			return new PlayerIdentity(player.getUUID(), name, "fabric");
		} catch (CommandSyntaxException exception) {
			return null;
		}
	}

	private static void sendFeedback(CommandSourceStack source, boolean success, String message) {
		Component text = Component.literal((success ? "§a✔ " : "§c❌ ") + message);
		if (success) {
			source.sendSuccess(() -> text, false);
			return;
		}

		source.sendFailure(text);
	}

	private record PlayerIdentity(UUID id, String name, String server) {
	}
}
