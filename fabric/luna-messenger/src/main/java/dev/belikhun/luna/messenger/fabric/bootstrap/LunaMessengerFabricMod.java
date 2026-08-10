package dev.belikhun.luna.messenger.fabric.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerCommandType;
import dev.belikhun.luna.core.api.messenger.MessengerMessages;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.mc.command.VanillaCommands;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.messenger.mc.runtime.MessengerRuntime;
import dev.belikhun.luna.messenger.mc.runtime.MessengerRuntimeFactory;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * The network chat and direct messaging commands, as the fabric build.
 *
 * Ordinary chat never reaches vanilla's broadcast: the messenger's whole point
 * is that a line typed here is seen on every server, which only the proxy can
 * do. {@code ALLOW_CHAT_MESSAGE} refusing the message is what stops it being
 * shown twice, once locally and once from the proxy.
 */
public final class LunaMessengerFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunamessenger";

	private static final String NOT_READY = "❌ LunaMessenger chưa sẵn sàng.";
	private static final String PLAYERS_ONLY = "❌ Lệnh này chỉ dùng cho người chơi.";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private MessengerRuntime messengerRuntime;

	public LunaMessengerFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaMessenger", true);
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (messengerRuntime != null) {
				messengerRuntime.publishJoin(handler.getPlayer(), false);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (messengerRuntime != null) {
				messengerRuntime.publishLeave(handler.getPlayer());
			}
		});

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> onChat(message.signedContent(), sender));
	}

	private void onServerStarted(MinecraftServer server) {
		dependencyManager = LunaCoreFabric.services().dependencyManager();
		messengerRuntime = MessengerRuntimeFactory.create(logger, server, dependencyManager);

		dependencyManager.registerSingleton(MessengerRuntime.class, messengerRuntime);
		logger.success("Luna Messenger Fabric runtime đã sẵn sàng.");
	}

	private void onServerStopping(MinecraftServer server) {
		if (dependencyManager != null) {
			dependencyManager.unregister(MessengerRuntime.class);
			dependencyManager = null;
		}

		if (messengerRuntime != null) {
			messengerRuntime.close();
			messengerRuntime = null;
		}
	}

	/** @return whether the game should go on to broadcast this line itself */
	private boolean onChat(String rawText, ServerPlayer sender) {
		if (messengerRuntime == null || sender == null) {
			return true;
		}

		if (rawText == null || rawText.isBlank()) {
			return true;
		}

		if (!messengerRuntime.sendCommand(sender, MessengerCommandType.SEND_CHAT, rawText.trim())) {
			sender.sendSystemMessage(mini(MessengerMessages.chatFailed()));
		}

		return false;
	}

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nw")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				MessengerCommandType.SWITCH_NETWORK,
				"",
				null,
				MessengerMessages.networkSwitchFailed()
			)));

		dispatcher.register(Commands.literal("sv")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				MessengerCommandType.SWITCH_SERVER,
				"",
				null,
				MessengerMessages.serverSwitchFailed()
			)));

		dispatcher.register(Commands.literal("poke")
			.executes(context -> reply(context.getSource(), pokeUsage()))
			.then(Commands.argument("target", StringArgumentType.word())
				.suggests(this::suggestDirectTargets)
				.executes(context -> executePoke(
					context.getSource(),
					StringArgumentType.getString(context, "target")
				))));

		// vanilla owns msg/tell/w as aliases of /tell, and brigadier merges a
		// same-named literal instead of replacing it, so its <targets> argument
		// would still match first; the paper build wins these outright
		if (!VanillaCommands.remove(dispatcher, "msg", "tell", "w")) {
			logger.warn("Không gỡ được lệnh /msg gốc của Minecraft. Lệnh nhắn tin của Luna có thể không hoạt động.");
		}

		for (String alias : MessengerMessages.DIRECT_COMMANDS) {
			dispatcher.register(Commands.literal(alias)
				.executes(context -> reply(context.getSource(), directUsage()))
				.then(Commands.argument("target", StringArgumentType.word())
					.suggests(this::suggestDirectTargets)
					.executes(context -> executeContextSwitch(
						context.getSource(),
						MessengerCommandType.SWITCH_DIRECT,
						StringArgumentType.getString(context, "target"),
						StringArgumentType.getString(context, "target"),
						MessengerMessages.directSwitchFailed()
					))
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(context -> executeDirectMessage(
							context.getSource(),
							StringArgumentType.getString(context, "target"),
							StringArgumentType.getString(context, "message")
						)))));
		}

		for (String alias : MessengerMessages.REPLY_COMMANDS) {
			dispatcher.register(Commands.literal(alias)
				.executes(context -> reply(context.getSource(), replyUsage()))
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(context -> executeReply(
						context.getSource(),
						StringArgumentType.getString(context, "message")
					))));
		}
	}

	private int executePoke(CommandSourceStack source, String targetName) {
		ServerPlayer player = playerOf(source);

		if (player == null) {
			return reply(source, PLAYERS_ONLY);
		}

		if (messengerRuntime == null) {
			return reply(source, NOT_READY);
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();

		if (normalizedTarget.isEmpty()) {
			return reply(source, pokeUsage());
		}

		if (!messengerRuntime.sendCommand(player, MessengerCommandType.SEND_POKE, normalizedTarget, normalizedTarget)) {
			return reply(source, "❌ Không thể gửi yêu cầu chọc lúc này.");
		}

		return 1;
	}

	private int executeDirectMessage(CommandSourceStack source, String targetName, String message) {
		ServerPlayer player = playerOf(source);

		if (player == null) {
			return reply(source, PLAYERS_ONLY);
		}

		if (messengerRuntime == null) {
			return reply(source, NOT_READY);
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();
		String normalizedMessage = message == null ? "" : message.trim();

		if (normalizedTarget.isEmpty() || normalizedMessage.isEmpty()) {
			return reply(source, directUsage());
		}

		if (!messengerRuntime.sendCommand(player, MessengerCommandType.SEND_DIRECT, normalizedMessage, normalizedTarget)) {
			return reply(source, "❌ Không thể gửi tin nhắn lúc này.");
		}

		return 1;
	}

	private int executeReply(CommandSourceStack source, String message) {
		ServerPlayer player = playerOf(source);

		if (player == null) {
			return reply(source, PLAYERS_ONLY);
		}

		if (messengerRuntime == null) {
			return reply(source, NOT_READY);
		}

		String normalizedMessage = message == null ? "" : message.trim();

		if (normalizedMessage.isEmpty()) {
			return reply(source, replyUsage());
		}

		if (!messengerRuntime.sendCommand(player, MessengerCommandType.SEND_REPLY, normalizedMessage)) {
			return reply(source, "❌ Không thể gửi tin nhắn trả lời lúc này.");
		}

		return 1;
	}

	private int executeContextSwitch(
		CommandSourceStack source,
		MessengerCommandType commandType,
		String argument,
		String targetName,
		String failureMessage
	) {
		ServerPlayer player = playerOf(source);

		if (player == null) {
			return reply(source, PLAYERS_ONLY);
		}

		if (messengerRuntime == null) {
			return reply(source, NOT_READY);
		}

		if (!messengerRuntime.sendCommand(player, commandType, argument, targetName)) {
			return reply(source, failureMessage);
		}

		return 1;
	}

	private CompletableFuture<Suggestions> suggestDirectTargets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		ServerPlayer player = playerOf(context.getSource());

		if (player == null || messengerRuntime == null) {
			return Suggestions.empty();
		}

		return SharedSuggestionProvider.suggest(
			messengerRuntime.suggestDirectTargets(builder.getRemaining(), player.getScoreboardName()),
			builder
		);
	}

	private ServerPlayer playerOf(CommandSourceStack source) {
		return source != null && source.getEntity() instanceof ServerPlayer player ? player : null;
	}

	/** Answer the command source; 0 is brigadier's "did nothing". */
	private int reply(CommandSourceStack source, String message) {
		source.sendSystemMessage(mini(message));
		return 0;
	}

	private String pokeUsage() {
		return MessengerMessages.pokeUsage();
	}

	private String directUsage() {
		return MessengerMessages.directUsage();
	}

	private String replyUsage() {
		return MessengerMessages.replyUsage();
	}

	/** Render one of the shared messenger strings; they are all MiniMessage. */
	private Component mini(String miniMessage) {
		return LunaTextComponents.mini(miniMessage == null ? "" : miniMessage);
	}

}
