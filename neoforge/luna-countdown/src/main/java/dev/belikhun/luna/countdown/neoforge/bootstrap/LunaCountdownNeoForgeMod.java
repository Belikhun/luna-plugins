package dev.belikhun.luna.countdown.neoforge.bootstrap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.countdown.CountdownMessages;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.countdown.mc.runtime.CountdownRuntime;
import dev.belikhun.luna.countdown.mc.runtime.CountdownRuntimeFactory;
import dev.belikhun.luna.countdown.mc.shutdown.ShutdownTimer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Mod(LunaCountdownNeoForgeMod.MOD_ID)
public final class LunaCountdownNeoForgeMod {
	public static final String MOD_ID = "lunacountdown";
	private static final String COUNTDOWN_PERMISSION = "countdown.countdown";
	private static final String SHUTDOWN_PERMISSION = "countdown.shutdown";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private CountdownRuntime countdownRuntime;
	private ShutdownTimer shutdownTimer;

	public LunaCountdownNeoForgeMod() {
		this.logger = LunaLoggers.create("LunaCountdown", true);
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		dependencyManager = LunaCore.services().dependencyManager();
		countdownRuntime = CountdownRuntimeFactory.create(logger, event.getServer());
		dependencyManager.registerSingleton(CountdownRuntime.class, countdownRuntime);
		shutdownTimer = new ShutdownTimer(logger, event.getServer());
		logger.success("Luna Countdown NeoForge runtime đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		registerCountdownCommand(event, "countdown");
		registerCountdownCommand(event, "cd");
		registerShutdownCommand(event, "shutdown");
		registerShutdownCommand(event, "stoptimer");
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (dependencyManager != null) {
			dependencyManager.unregister(CountdownRuntime.class);
		}

		if (countdownRuntime != null) {
			countdownRuntime.close();
			countdownRuntime = null;
		}

		if (shutdownTimer != null) {
			shutdownTimer.close();
			shutdownTimer = null;
		}

		dependencyManager = null;
	}

	private void registerCountdownCommand(RegisterCommandsEvent event, String root) {
		event.getDispatcher().register(Commands.literal(root)
			.requires(source -> hasPermission(source, COUNTDOWN_PERMISSION))
			.executes(context -> sendCountdownUsage(context.getSource(), root))
			.then(Commands.literal("start")
				.executes(context -> sendCountdownStartUsage(context.getSource(), root))
				.then(Commands.argument("length", StringArgumentType.word())
					.suggests(this::suggestCountdownLengths)
					.executes(context -> executeCountdownStart(
						context.getSource(),
						root,
						StringArgumentType.getString(context, "length"),
						"Sự Kiện Kết Thúc"
					))
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.suggests(this::suggestCountdownTitles)
						.executes(context -> executeCountdownStart(
							context.getSource(),
							root,
							StringArgumentType.getString(context, "length"),
							StringArgumentType.getString(context, "message")
						)))))
			.then(Commands.literal("stop")
				.executes(context -> sendCountdownStopUsage(context.getSource(), root))
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
					.suggests(this::suggestActiveCountdownIds)
					.executes(context -> executeCountdownStop(
						context.getSource(),
						IntegerArgumentType.getInteger(context, "id")
					))))
			.then(Commands.literal("stopall")
				.executes(context -> executeCountdownStopAll(context.getSource()))));
	}

	private void registerShutdownCommand(RegisterCommandsEvent event, String root) {
		event.getDispatcher().register(Commands.literal(root)
			.requires(source -> hasPermission(source, SHUTDOWN_PERMISSION))
			.executes(context -> sendShutdownUsage(context.getSource(), root))
			.then(Commands.literal("cancel")
				.executes(context -> executeShutdownCancel(context.getSource())))
			.then(Commands.argument("length", StringArgumentType.word())
				.suggests(this::suggestShutdownLengths)
				.executes(context -> executeShutdownStart(
					context.getSource(),
					StringArgumentType.getString(context, "length"),
					null
				))
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.suggests(this::suggestShutdownReasons)
					.executes(context -> executeShutdownStart(
						context.getSource(),
						StringArgumentType.getString(context, "length"),
						StringArgumentType.getString(context, "message")
					)))));
	}

	private int sendCountdownUsage(CommandSourceStack source, String root) {
		source.sendSystemMessage(mini(CountdownMessages.countdownUsage(root)));
		return 0;
	}

	private int sendCountdownStartUsage(CommandSourceStack source, String root) {
		source.sendSystemMessage(mini(CountdownMessages.countdownStartUsage(root)));
		return 0;
	}

	private int sendCountdownStopUsage(CommandSourceStack source, String root) {
		source.sendSystemMessage(mini(CountdownMessages.countdownStopUsage(root)));
		return 0;
	}

	private int sendShutdownUsage(CommandSourceStack source, String root) {
		source.sendSystemMessage(mini(CountdownMessages.shutdownUsage(root)));
		return 0;
	}

	private int executeCountdownStart(CommandSourceStack source, String root, String lengthInput, String title) {
		if (countdownRuntime == null) {
			source.sendSystemMessage(mini(CountdownMessages.notReady()));
			return 0;
		}

		int length = parseTime(lengthInput);
		if (length <= 0) {
			source.sendSystemMessage(mini(CountdownMessages.invalidTime(lengthInput)));
			return 0;
		}

		String normalizedTitle = safe(title).isBlank() ? "Sự Kiện Kết Thúc" : title.trim();
		int id = countdownRuntime.start(normalizedTitle, length);
		source.sendSystemMessage(Component.literal(
			"✔ Đã tạo countdown #" + id + " cho " + normalizedTitle + " trong " + readableTime(length) + "."
		));
		return 1;
	}

	private int executeCountdownStop(CommandSourceStack source, int id) {
		if (countdownRuntime == null) {
			source.sendSystemMessage(mini(CountdownMessages.notReady()));
			return 0;
		}

		if (!countdownRuntime.stop(id, "Đã hủy.")) {
			source.sendSystemMessage(mini(CountdownMessages.countdownNotFound(id)));
			return 0;
		}

		source.sendSystemMessage(mini(CountdownMessages.countdownStopped(id)));
		return 1;
	}

	private int executeCountdownStopAll(CommandSourceStack source) {
		if (countdownRuntime == null) {
			source.sendSystemMessage(mini(CountdownMessages.notReady()));
			return 0;
		}

		countdownRuntime.stopAll("Đã hủy.");
		source.sendSystemMessage(mini(CountdownMessages.allCountdownsStopped()));
		return 1;
	}

	private int executeShutdownStart(CommandSourceStack source, String lengthInput, String reason) {
		if (shutdownTimer == null) {
			source.sendSystemMessage(mini(CountdownMessages.notReady()));
			return 0;
		}

		int length = parseTime(lengthInput);
		if (length <= 0) {
			source.sendSystemMessage(mini(CountdownMessages.invalidTime(lengthInput)));
			return 0;
		}

		if (!shutdownTimer.start(length, reason)) {
			source.sendSystemMessage(mini(CountdownMessages.shutdownAlreadyScheduled()));
			return 0;
		}

		source.sendSystemMessage(mini(CountdownMessages.shutdownScheduled(readableTime(length))));
		return 1;
	}

	private int executeShutdownCancel(CommandSourceStack source) {
		if (shutdownTimer == null) {
			source.sendSystemMessage(mini(CountdownMessages.notReady()));
			return 0;
		}

		if (!shutdownTimer.cancel("Đã hủy tắt máy chủ.")) {
			source.sendSystemMessage(mini(CountdownMessages.noShutdownScheduled()));
			return 0;
		}

		source.sendSystemMessage(mini(CountdownMessages.shutdownCancelled()));
		return 1;
	}

	private int parseTime(String input) {
		try {
			if (input == null || input.isBlank()) {
				return -1;
			}

			String value = input.trim().toLowerCase(Locale.ROOT);
			if (value.endsWith("d")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 86400;
			}
			if (value.endsWith("h")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 3600;
			}
			if (value.endsWith("m")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 60;
			}
			if (value.endsWith("s")) {
				return Integer.parseInt(value.substring(0, value.length() - 1));
			}
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private String readableTime(int seconds) {
		return dev.belikhun.luna.core.api.string.Formatters.compactDuration(Duration.ofSeconds(Math.max(1L, seconds)));
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private boolean hasPermission(CommandSourceStack source, String permission) {
		if (source == null || permission == null || permission.isBlank()) {
			return false;
		}

		if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			return true;
		}

		PermissionService permissionService = LunaCore.services().dependencyManager()
			.resolveOptional(PermissionService.class)
			.orElse(null);
		return permissionService != null && permissionService.hasPermission(player.getUUID(), permission);
	}

	private CompletableFuture<Suggestions> suggestCountdownLengths(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
			CommandCompletions.filterPrefix(List.of("10", "30", "60", "120", "300", "30s", "1m", "5m", "10m"), builder.getRemaining()),
			builder
		);
	}

	private CompletableFuture<Suggestions> suggestCountdownTitles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
			CommandCompletions.filterPrefix(List.of("Sự_Kiện_Kết_Thúc", "Bắt_Đầu_Boss", "Mở_Cổng", "Bảo_Trì"), builder.getRemaining()),
			builder
		);
	}

	private CompletableFuture<Suggestions> suggestActiveCountdownIds(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		if (countdownRuntime == null) {
			return Suggestions.empty();
		}

		List<String> ids = countdownRuntime.activeCountdowns().stream()
			.map(snapshot -> Integer.toString(snapshot.id()))
			.toList();
		return SharedSuggestionProvider.suggest(CommandCompletions.filterPrefix(ids, builder.getRemaining()), builder);
	}

	private CompletableFuture<Suggestions> suggestShutdownLengths(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
			CommandCompletions.filterPrefix(List.of("30", "60", "120", "300", "30s", "1m", "5m", "10m"), builder.getRemaining()),
			builder
		);
	}

	private CompletableFuture<Suggestions> suggestShutdownReasons(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
			CommandCompletions.filterPrefix(List.of("Bảo_trì", "Khởi_động_lại", "Cập_nhật_hệ_thống"), builder.getRemaining()),
			builder
		);
	}

	/** Render one of the shared countdown strings; they are all MiniMessage. */
	private Component mini(String miniMessage) {
		return LunaTextComponents.mini(miniMessage == null ? "" : miniMessage);
	}

}
