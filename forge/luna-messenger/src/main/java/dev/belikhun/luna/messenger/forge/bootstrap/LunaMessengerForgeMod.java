package dev.belikhun.luna.messenger.forge.bootstrap;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.MessengerMessages;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.command.VanillaCommands;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.messenger.mc.runtime.MessengerRuntime;
import dev.belikhun.luna.messenger.mc.runtime.MessengerRuntimeFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.concurrent.CompletableFuture;

@Mod(LunaMessengerForgeMod.MOD_ID)
public final class LunaMessengerForgeMod {
	public static final String MOD_ID = "lunamessenger";


	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private MessengerRuntime messengerRuntime;

	public LunaMessengerForgeMod() {
		this.logger = LunaLoggers.create("LunaMessenger", true);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaMessenger Forge sẽ không khởi động.");
			return;
		}

		dependencyManager = LunaCore.services().dependencyManager();
		messengerRuntime = MessengerRuntimeFactory.create(logger, event.getServer(), dependencyManager);
		dependencyManager.registerSingleton(MessengerRuntime.class, messengerRuntime);
		logger.success("Luna Messenger Forge runtime đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("nw")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_NETWORK,
				"",
				null,
				MessengerMessages.networkSwitchFailed()
			)));

		event.getDispatcher().register(Commands.literal("sv")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_SERVER,
				"",
				null,
				MessengerMessages.serverSwitchFailed()
			)));

		event.getDispatcher().register(Commands.literal("poke")
			.executes(context -> sendPokeUsage(context.getSource()))
			.then(Commands.argument("target", StringArgumentType.word())
				.suggests(this::suggestDirectTargets)
				.executes(context -> executePoke(
					context.getSource(),
					StringArgumentType.getString(context, "target")
				))));

		// vanilla owns msg/tell/w as aliases of /tell, and brigadier merges a
		// same-named literal instead of replacing it, so its <targets> argument
		// would still match first; the paper build wins these outright
		if (!VanillaCommands.remove(event.getDispatcher(), "msg", "tell", "w")) {
			logger.warn("Không gỡ được lệnh /msg gốc của Minecraft. Lệnh nhắn tin của Luna có thể không hoạt động.");
		}

		for (String alias : MessengerMessages.DIRECT_COMMANDS) {
			event.getDispatcher().register(Commands.literal(alias)
				.executes(context -> sendDirectUsage(context.getSource()))
				.then(Commands.argument("target", StringArgumentType.word())
					.suggests(this::suggestDirectTargets)
					.executes(context -> executeContextSwitch(
						context.getSource(),
						dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_DIRECT,
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
			event.getDispatcher().register(Commands.literal(alias)
				.executes(context -> sendReplyUsage(context.getSource()))
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(context -> executeReply(
						context.getSource(),
						StringArgumentType.getString(context, "message")
					))));
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (messengerRuntime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			return;
		}

		messengerRuntime.publishJoin(player, false);
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (messengerRuntime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			return;
		}

		messengerRuntime.publishLeave(player);
	}

	@SubscribeEvent
	public void onServerChat(ServerChatEvent event) {
		if (messengerRuntime == null) {
			return;
		}

		ServerPlayer player = event.getPlayer();
		if (player == null) {
			return;
		}

		String message = event.getRawText();
		if (message == null || message.isBlank()) {
			return;
		}

		event.setCanceled(true);
		boolean sent = messengerRuntime.sendCommand(
			player,
			dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_CHAT,
			message.trim()
		);
		if (!sent) {
			player.sendSystemMessage(mini(MessengerMessages.chatFailed()));
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (dependencyManager != null) {
			dependencyManager.unregister(MessengerRuntime.class);
		}

		if (messengerRuntime != null) {
			messengerRuntime.close();
			messengerRuntime = null;
		}

		dependencyManager = null;
	}

	private int sendPokeUsage(CommandSourceStack source) {
		source.sendSystemMessage(mini(MessengerMessages.pokeUsage()));
		return 0;
	}

	private int sendDirectUsage(CommandSourceStack source) {
		source.sendSystemMessage(mini(MessengerMessages.directUsage()));
		return 0;
	}

	private int sendReplyUsage(CommandSourceStack source) {
		source.sendSystemMessage(mini(MessengerMessages.replyUsage()));
		return 0;
	}

	private int executePoke(CommandSourceStack source, String targetName) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(mini(MessengerMessages.notAPlayer()));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(mini(MessengerMessages.notReady()));
			return 0;
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();
		if (normalizedTarget.isEmpty()) {
			player.sendSystemMessage(mini(MessengerMessages.pokeUsage()));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(player, dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_POKE, normalizedTarget, normalizedTarget);
		if (!sent) {
			player.sendSystemMessage(mini(MessengerMessages.pokeFailed()));
			return 0;
		}

		return 1;
	}

	private int executeDirectMessage(CommandSourceStack source, String targetName, String message) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(mini(MessengerMessages.notAPlayer()));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(mini(MessengerMessages.notReady()));
			return 0;
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();
		String normalizedMessage = message == null ? "" : message.trim();
		if (normalizedTarget.isEmpty() || normalizedMessage.isEmpty()) {
			player.sendSystemMessage(mini(MessengerMessages.directSendUsage()));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(
			player,
			dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_DIRECT,
			normalizedMessage,
			normalizedTarget
		);
		if (!sent) {
			player.sendSystemMessage(mini(MessengerMessages.directFailed()));
			return 0;
		}

		return 1;
	}

	private int executeReply(CommandSourceStack source, String message) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(mini(MessengerMessages.notAPlayer()));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(mini(MessengerMessages.notReady()));
			return 0;
		}

		String normalizedMessage = message == null ? "" : message.trim();
		if (normalizedMessage.isEmpty()) {
			player.sendSystemMessage(mini(MessengerMessages.replyUsage()));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(
			player,
			dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_REPLY,
			normalizedMessage
		);
		if (!sent) {
			player.sendSystemMessage(mini(MessengerMessages.replyFailed()));
			return 0;
		}

		return 1;
	}

	private int executeContextSwitch(
		CommandSourceStack source,
		dev.belikhun.luna.core.api.messenger.MessengerCommandType commandType,
		String argument,
		String targetName,
		String failureMessage
	) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(mini(MessengerMessages.notAPlayer()));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(mini(MessengerMessages.notReady()));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(player, commandType, argument, targetName);
		if (!sent) {
			player.sendSystemMessage(mini(failureMessage));
			return 0;
		}

		return 1;
	}

	private CompletableFuture<Suggestions> suggestDirectTargets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		if (!(context.getSource().getEntity() instanceof ServerPlayer player) || messengerRuntime == null) {
			return Suggestions.empty();
		}

		return SharedSuggestionProvider.suggest(
			messengerRuntime.suggestDirectTargets(builder.getRemaining(), player.getGameProfile().getName()),
			builder
		);
	}

	/** Render one of the shared messenger strings; they are all MiniMessage. */
	private Component mini(String miniMessage) {
		return LunaTextComponents.mini(miniMessage == null ? "" : miniMessage);
	}

}
