package dev.belikhun.luna.messenger.neoforge.bootstrap;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
import dev.belikhun.luna.messenger.neoforge.runtime.NeoForgeMessengerRuntime;
import dev.belikhun.luna.messenger.neoforge.runtime.NeoForgeMessengerRuntimeFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.concurrent.CompletableFuture;

@Mod(LunaMessengerNeoForgeMod.MOD_ID)
public final class LunaMessengerNeoForgeMod {
	public static final String MOD_ID = "lunamessenger";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private NeoForgeMessengerRuntime messengerRuntime;

	public LunaMessengerNeoForgeMod() {
		this.logger = NeoForgeLunaLoggers.create("LunaMessengerNeoForge", true).scope("MessengerNeoForge");
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCoreNeoForge.services().dependencyManager();
		messengerRuntime = NeoForgeMessengerRuntimeFactory.create(logger, dependencyManager);
		dependencyManager.registerSingleton(NeoForgeMessengerRuntime.class, messengerRuntime);
		logger.success("Luna Messenger NeoForge runtime đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("nw")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_NETWORK,
				"",
				null,
				"❌ Không thể chuyển sang kênh mạng lúc này."
			)));

		event.getDispatcher().register(Commands.literal("sv")
			.executes(context -> executeContextSwitch(
				context.getSource(),
				dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_SERVER,
				"",
				null,
				"❌ Không thể chuyển sang kênh máy chủ lúc này."
			)));

		event.getDispatcher().register(Commands.literal("poke")
			.executes(context -> sendPokeUsage(context.getSource()))
			.then(Commands.argument("target", StringArgumentType.word())
				.suggests(this::suggestDirectTargets)
				.executes(context -> executePoke(
					context.getSource(),
					StringArgumentType.getString(context, "target")
				))));

		event.getDispatcher().register(Commands.literal("msg")
			.executes(context -> sendDirectUsage(context.getSource()))
			.then(Commands.argument("target", StringArgumentType.word())
				.suggests(this::suggestDirectTargets)
				.executes(context -> executeContextSwitch(
					context.getSource(),
					dev.belikhun.luna.core.api.messenger.MessengerCommandType.SWITCH_DIRECT,
					StringArgumentType.getString(context, "target"),
					StringArgumentType.getString(context, "target"),
					"❌ Không thể chuyển sang nhắn tin trực tiếp lúc này."
				))
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(context -> executeDirectMessage(
						context.getSource(),
						StringArgumentType.getString(context, "target"),
						StringArgumentType.getString(context, "message")
					)))));

		event.getDispatcher().register(Commands.literal("r")
			.executes(context -> sendReplyUsage(context.getSource()))
			.then(Commands.argument("message", StringArgumentType.greedyString())
				.executes(context -> executeReply(
					context.getSource(),
					StringArgumentType.getString(context, "message")
				))));
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
			player.sendSystemMessage(Component.literal("❌ Không thể gửi chat messenger lúc này."));
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (dependencyManager != null) {
			dependencyManager.unregister(NeoForgeMessengerRuntime.class);
		}

		if (messengerRuntime != null) {
			messengerRuntime.close();
			messengerRuntime = null;
		}

		dependencyManager = null;
	}

	private int sendPokeUsage(CommandSourceStack source) {
		source.sendSystemMessage(Component.literal(CommandStrings.plainUsage("/poke", CommandStrings.required("người_chơi", "text"))));
		return 0;
	}

	private int sendDirectUsage(CommandSourceStack source) {
		source.sendSystemMessage(Component.literal(CommandStrings.plainUsage(
			"/msg",
			CommandStrings.required("người_chơi", "text"),
			CommandStrings.required("nội_dung", "text")
		)));
		return 0;
	}

	private int sendReplyUsage(CommandSourceStack source) {
		source.sendSystemMessage(Component.literal(CommandStrings.plainUsage(
			"/r",
			CommandStrings.required("nội_dung", "text")
		)));
		return 0;
	}

	private int executePoke(CommandSourceStack source, String targetName) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(Component.literal("❌ LunaMessenger NeoForge chưa sẵn sàng."));
			return 0;
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();
		if (normalizedTarget.isEmpty()) {
			player.sendSystemMessage(Component.literal(CommandStrings.plainUsage("/poke", CommandStrings.required("người_chơi", "text"))));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(player, dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_POKE, normalizedTarget, normalizedTarget);
		if (!sent) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi yêu cầu chọc lúc này."));
			return 0;
		}

		return 1;
	}

	private int executeDirectMessage(CommandSourceStack source, String targetName, String message) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(Component.literal("❌ LunaMessenger NeoForge chưa sẵn sàng."));
			return 0;
		}

		String normalizedTarget = targetName == null ? "" : targetName.trim();
		String normalizedMessage = message == null ? "" : message.trim();
		if (normalizedTarget.isEmpty() || normalizedMessage.isEmpty()) {
			player.sendSystemMessage(Component.literal(CommandStrings.plainUsage(
				"/msg",
				CommandStrings.required("người_chơi", "text"),
				CommandStrings.required("nội_dung", "text")
			)));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(
			player,
			dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_DIRECT,
			normalizedMessage,
			normalizedTarget
		);
		if (!sent) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi tin nhắn lúc này."));
			return 0;
		}

		return 1;
	}

	private int executeReply(CommandSourceStack source, String message) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(Component.literal("❌ LunaMessenger NeoForge chưa sẵn sàng."));
			return 0;
		}

		String normalizedMessage = message == null ? "" : message.trim();
		if (normalizedMessage.isEmpty()) {
			player.sendSystemMessage(Component.literal(CommandStrings.plainUsage(
				"/r",
				CommandStrings.required("nội_dung", "text")
			)));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(
			player,
			dev.belikhun.luna.core.api.messenger.MessengerCommandType.SEND_REPLY,
			normalizedMessage
		);
		if (!sent) {
			player.sendSystemMessage(Component.literal("❌ Không thể gửi tin nhắn trả lời lúc này."));
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
			source.sendSystemMessage(Component.literal("❌ Lệnh này chỉ dùng cho người chơi."));
			return 0;
		}

		if (messengerRuntime == null) {
			player.sendSystemMessage(Component.literal("❌ LunaMessenger NeoForge chưa sẵn sàng."));
			return 0;
		}

		boolean sent = messengerRuntime.sendCommand(player, commandType, argument, targetName);
		if (!sent) {
			player.sendSystemMessage(Component.literal(failureMessage));
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
}
