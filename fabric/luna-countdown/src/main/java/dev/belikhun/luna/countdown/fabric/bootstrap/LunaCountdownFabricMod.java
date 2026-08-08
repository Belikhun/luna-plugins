package dev.belikhun.luna.countdown.fabric.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.countdown.fabric.runtime.FabricCountdownRuntime;
import dev.belikhun.luna.countdown.fabric.shutdown.FabricShutdownTimer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Event countdowns and scheduled shutdowns, as the fabric build.
 *
 * The commands are registered before the server has started - that is when
 * fabric fires the callback - so every one of them resolves the runtime at
 * execution time and reports plainly when it is not up yet.
 *
 * One build covers Minecraft 1.20 upward, on the same terms as LunaCore: no
 * subclassing of game types, no mixins, and only API that has held across that
 * range. The action bar is the one place that needed care; see
 * {@code text/Broadcasts}.
 */
public final class LunaCountdownFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunacountdown";
	private static final String COUNTDOWN_PERMISSION = "countdown.countdown";
	private static final String SHUTDOWN_PERMISSION = "countdown.shutdown";
	private static final String DEFAULT_COUNTDOWN_TITLE = "Sự Kiện Kết Thúc";
	private static final String NOT_READY = "❌ LunaCountdown chưa sẵn sàng.";

	private final LunaLogger logger;
	private DependencyManager dependencyManager;
	private FabricCountdownRuntime countdownRuntime;
	private FabricShutdownTimer shutdownTimer;

	public LunaCountdownFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaCountdown", true);
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCountdownCommand(dispatcher, "countdown");
			registerCountdownCommand(dispatcher, "cd");
			registerShutdownCommand(dispatcher, "shutdown");
			registerShutdownCommand(dispatcher, "stoptimer");
		});
	}

	private void onServerStarted(MinecraftServer server) {
		dependencyManager = LunaCoreFabric.services().dependencyManager();
		countdownRuntime = new FabricCountdownRuntime(logger, server);
		shutdownTimer = new FabricShutdownTimer(logger, server);

		dependencyManager.registerSingleton(FabricCountdownRuntime.class, countdownRuntime);
		dependencyManager.registerSingleton(FabricShutdownTimer.class, shutdownTimer);

		if (permissionService() == null) {
			logger.warn("Không tìm thấy PermissionService. Chỉ console dùng được các lệnh countdown và shutdown.");
		}

		logger.success("Luna Countdown Fabric runtime đã sẵn sàng.");
	}

	private void onServerStopping(MinecraftServer server) {
		if (dependencyManager != null) {
			dependencyManager.unregister(FabricCountdownRuntime.class);
			dependencyManager.unregister(FabricShutdownTimer.class);
			dependencyManager = null;
		}

		if (countdownRuntime != null) {
			countdownRuntime.close();
			countdownRuntime = null;
		}

		if (shutdownTimer != null) {
			shutdownTimer.close();
			shutdownTimer = null;
		}
	}

	private void registerCountdownCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.requires(source -> hasPermission(source, COUNTDOWN_PERMISSION))
			.executes(context -> sendCountdownUsage(context.getSource(), root))
			.then(Commands.literal("start")
				.executes(context -> sendCountdownStartUsage(context.getSource(), root))
				.then(Commands.argument("length", StringArgumentType.word())
					.suggests(this::suggestCountdownLengths)
					.executes(context -> executeCountdownStart(
						context.getSource(),
						StringArgumentType.getString(context, "length"),
						DEFAULT_COUNTDOWN_TITLE
					))
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.suggests(this::suggestCountdownTitles)
						.executes(context -> executeCountdownStart(
							context.getSource(),
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

	private void registerShutdownCommand(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
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
		return reply(source, CommandStrings.plainUsage(
			"/" + root,
			CommandStrings.required("start|stop|stopall", "action")
		));
	}

	private int sendCountdownStartUsage(CommandSourceStack source, String root) {
		return reply(source, CommandStrings.plainUsage(
			"/" + root,
			CommandStrings.literal("start"),
			CommandStrings.required("length", "time"),
			CommandStrings.optional("message", "text")
		));
	}

	private int sendCountdownStopUsage(CommandSourceStack source, String root) {
		return reply(source, CommandStrings.plainUsage(
			"/" + root,
			CommandStrings.literal("stop"),
			CommandStrings.required("id", "number")
		));
	}

	private int sendShutdownUsage(CommandSourceStack source, String root) {
		return reply(source, CommandStrings.plainUsage(
			"/" + root,
			CommandStrings.required("length|cancel", "time|action"),
			CommandStrings.optional("message", "text")
		));
	}

	private int executeCountdownStart(CommandSourceStack source, String lengthInput, String title) {
		if (countdownRuntime == null) {
			return reply(source, NOT_READY);
		}

		int length = parseTime(lengthInput);

		if (length <= 0) {
			return reply(source, "❌ Thời gian không hợp lệ: " + lengthInput);
		}

		String normalizedTitle = title == null || title.isBlank() ? DEFAULT_COUNTDOWN_TITLE : title.trim();
		int id = countdownRuntime.start(normalizedTitle, length);

		return succeed(source, "✔ Đã tạo countdown #" + id + " cho " + normalizedTitle
			+ " trong " + readableTime(length) + ".");
	}

	private int executeCountdownStop(CommandSourceStack source, int id) {
		if (countdownRuntime == null) {
			return reply(source, NOT_READY);
		}

		if (!countdownRuntime.stop(id, "Đã hủy.")) {
			return reply(source, "❌ Không tìm thấy countdown với ID " + id + ".");
		}

		return succeed(source, "✔ Đã dừng countdown #" + id + ".");
	}

	private int executeCountdownStopAll(CommandSourceStack source) {
		if (countdownRuntime == null) {
			return reply(source, NOT_READY);
		}

		countdownRuntime.stopAll("Đã hủy.");

		return succeed(source, "✔ Đã dừng toàn bộ countdown đang hoạt động.");
	}

	private int executeShutdownStart(CommandSourceStack source, String lengthInput, String reason) {
		if (shutdownTimer == null) {
			return reply(source, NOT_READY);
		}

		int length = parseTime(lengthInput);

		if (length <= 0) {
			return reply(source, "❌ Thời gian không hợp lệ: " + lengthInput);
		}

		if (!shutdownTimer.start(length, reason)) {
			return reply(source, "❌ Tắt máy chủ đã được lên lịch. Hủy bằng /shutdown cancel.");
		}

		return succeed(source, "✔ Đã lên lịch tắt máy chủ sau " + readableTime(length) + ".");
	}

	private int executeShutdownCancel(CommandSourceStack source) {
		if (shutdownTimer == null) {
			return reply(source, NOT_READY);
		}

		if (!shutdownTimer.cancel("Đã hủy tắt máy chủ.")) {
			return reply(source, "❌ Không có lịch tắt máy chủ.");
		}

		return succeed(source, "✔ Đã hủy tắt máy chủ.");
	}

	/** Answer the command source; 0 is brigadier's "did nothing". */
	private int reply(CommandSourceStack source, String message) {
		source.sendSystemMessage(Component.literal(message));
		return 0;
	}

	private int succeed(CommandSourceStack source, String message) {
		source.sendSystemMessage(Component.literal(message));
		return 1;
	}

	/** Seconds from a plain number or a d/h/m/s suffix; -1 when it is neither. */
	private int parseTime(String input) {
		if (input == null || input.isBlank()) {
			return -1;
		}

		String value = input.trim().toLowerCase(Locale.ROOT);
		int multiplier = switch (value.charAt(value.length() - 1)) {
			case 'd' -> 86400;
			case 'h' -> 3600;
			case 'm' -> 60;
			case 's' -> 1;
			default -> 0;
		};

		try {
			if (multiplier == 0) {
				return Integer.parseInt(value);
			}

			return Integer.parseInt(value.substring(0, value.length() - 1)) * multiplier;
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private String readableTime(int seconds) {
		return Formatters.compactDuration(Duration.ofSeconds(Math.max(1L, seconds)));
	}

	/**
	 * Whether the source may run the command.
	 *
	 * Anything that is not a player - the console, a command block, the console
	 * drawer in the luna web console - is allowed through, because those already
	 * hold the server. A player needs the permission, which means that without a
	 * PermissionService installed the commands are console-only.
	 */
	private boolean hasPermission(CommandSourceStack source, String permission) {
		if (source == null) {
			return false;
		}

		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return true;
		}

		PermissionService permissionService = permissionService();

		return permissionService != null && permissionService.hasPermission(player.getUUID(), permission);
	}

	private PermissionService permissionService() {
		if (dependencyManager == null) {
			return null;
		}

		return dependencyManager.resolveOptional(PermissionService.class).orElse(null);
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
}
