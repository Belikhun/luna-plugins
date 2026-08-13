package dev.belikhun.luna.countdown.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.countdown.mc12.CountdownRuntimeFactory;
import dev.belikhun.luna.countdown.mc12.ShutdownTimer;
import dev.belikhun.luna.legacy.countdown.CountdownMessages;
import dev.belikhun.luna.legacy.countdown.CountdownRuntime;
import dev.belikhun.luna.legacy.countdown.CountdownSnapshot;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.string.CommandCompletions;
import dev.belikhun.luna.legacy.string.Formatters;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * `/countdown` and `/shutdown` on 1.12.2.
 *
 * Brigadier arrived in 1.13, so the two commands the modern builds declare as
 * trees are hand-parsed `CommandBase`s here. That is the only real departure:
 * the wording, the permission nodes and the runtime underneath are shared.
 */
@Mod(
	modid = LunaCountdownMc12Mod.MOD_ID,
	name = "LunaCountdown",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaCountdownMc12Mod {
	public static final String MOD_ID = "lunacountdown";

	private static final String COUNTDOWN_PERMISSION = "countdown.countdown";
	private static final String SHUTDOWN_PERMISSION = "countdown.shutdown";

	private LunaLogger logger;
	private CountdownRuntime runtime;
	private ShutdownTimer shutdownTimer;
	private PermissionService permissions;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaCountdown");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		MinecraftServer server = event.getServer();

		permissions = LunaCore.find(PermissionService.class);

		if (permissions == null) {
			logger.warn("Không tìm thấy permission service; chỉ op mới dùng được lệnh countdown.");
		}

		runtime = CountdownRuntimeFactory.create(logger, server);
		shutdownTimer = new ShutdownTimer(logger, server);

		event.registerServerCommand(new CountdownCommand());
		event.registerServerCommand(new ShutdownCommand());

		logger.success("LunaCountdown (Forge 1.12.2) đã sẵn sàng.");
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (shutdownTimer != null) {
			shutdownTimer.close();
			shutdownTimer = null;
		}

		if (runtime != null) {
			runtime.close();
			runtime = null;
		}

		logger.audit("LunaCountdown đã dừng.");
	}

	/**
	 * Whether this sender may run a countdown verb.
	 *
	 * The console always may. A player is checked against the mirror, and with no
	 * mirror at all falls back to op - the opposite default to hat's, and
	 * deliberately: scheduling a server shutdown is an admin verb, so an unknown
	 * answer has to mean no.
	 */
	private boolean mayUse(ICommandSender sender, String permission) {
		if (!(sender instanceof EntityPlayerMP)) {
			return true;
		}

		EntityPlayerMP player = (EntityPlayerMP) sender;

		if (permissions == null || !permissions.isAvailable()) {
			return player.canUseCommand(2, "luna.countdown");
		}

		return permissions.hasPermission(player.getUniqueID(), permission);
	}

	private static void reply(ICommandSender sender, String miniMessage) {
		sender.sendMessage(LunaTextComponents.mini(miniMessage));
	}

	/** Seconds from `90`, `90s`, `5m`, `2h`; -1 when it is not a duration. */
	private static int parseSeconds(String raw) {
		if (raw == null || raw.isEmpty()) {
			return -1;
		}

		String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
		int multiplier = 1;
		char unit = text.charAt(text.length() - 1);

		if (unit == 's' || unit == 'm' || unit == 'h') {
			multiplier = unit == 's' ? 1 : unit == 'm' ? 60 : 3600;
			text = text.substring(0, text.length() - 1);
		}

		try {
			int value = Integer.parseInt(text);

			return value <= 0 ? -1 : value * multiplier;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private static String joinFrom(String[] args, int index) {
		if (args.length <= index) {
			return null;
		}

		StringBuilder out = new StringBuilder();

		for (int at = index; at < args.length; at += 1) {
			if (out.length() > 0) {
				out.append(' ');
			}

			out.append(args[at]);
		}

		return out.toString();
	}

	/**
	 * The permission gate, in the place vanilla already asks.
	 *
	 * `CommandBase` defaults to requiring op and checks it before dispatch, which
	 * would put these behind op whatever the mirror says. Overriding it with the
	 * real node keeps the behaviour the modern builds get from brigadier's
	 * `.requires(...)`: a player without the node does not see the command and is
	 * told it is unknown, rather than being told they are not allowed. That reads
	 * oddly in isolation and is still right - the same feature must not answer
	 * differently on one version of the fleet.
	 */
	private abstract class LunaCommandBase extends CommandBase {
		private final String permission;

		LunaCommandBase(String permission) {
			this.permission = permission;
		}

		@Override
		public int getRequiredPermissionLevel() {
			return 0;
		}

		@Override
		public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
			return mayUse(sender, permission);
		}
	}

	private final class CountdownCommand extends LunaCommandBase {
		private static final String ROOT = "countdown";

		CountdownCommand() {
			super(COUNTDOWN_PERMISSION);
		}

		@Override
		public String getName() {
			return ROOT;
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/countdown <start|stop|stopall>";
		}

		@Override
		public List<String> getTabCompletions(
			MinecraftServer server,
			ICommandSender sender,
			String[] args,
			net.minecraft.util.math.BlockPos pos
		) {
			if (args.length == 1) {
				return CommandCompletions.filterPrefix(Arrays.asList("start", "stop", "stopall"), args[0]);
			}

			return new ArrayList<String>();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			if (runtime == null) {
				reply(sender, CountdownMessages.notReady());
				return;
			}

			String action = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);

			if ("start".equals(action)) {
				start(sender, args);
				return;
			}

			if ("stop".equals(action)) {
				stop(sender, args);
				return;
			}

			if ("stopall".equals(action)) {
				runtime.stopAll("Đã dừng toàn bộ.");
				reply(sender, CountdownMessages.allCountdownsStopped());
				return;
			}

			reply(sender, CountdownMessages.countdownUsage(ROOT));

			for (CountdownSnapshot snapshot : runtime.activeCountdowns()) {
				reply(sender, CountdownMessages.countdownBar(
					snapshot.id(),
					snapshot.title(),
					snapshot.remainingSeconds()
				));
			}
		}

		private void start(ICommandSender sender, String[] args) {
			if (args.length < 2) {
				reply(sender, CountdownMessages.countdownStartUsage(ROOT));
				return;
			}

			int seconds = parseSeconds(args[1]);

			if (seconds < 0) {
				reply(sender, CountdownMessages.invalidTime(args[1]));
				return;
			}

			String title = joinFrom(args, 2);
			int id = runtime.start(title, seconds);

			logger.audit("Người dùng " + sender.getName() + " tạo countdown #" + id + ".");
		}

		private void stop(ICommandSender sender, String[] args) {
			if (args.length < 2) {
				reply(sender, CountdownMessages.countdownStopUsage(ROOT));
				return;
			}

			int id;

			try {
				id = Integer.parseInt(args[1]);
			} catch (NumberFormatException ignored) {
				reply(sender, CountdownMessages.invalidId(args[1]));
				return;
			}

			if (!runtime.stop(id, "Đã dừng bởi " + sender.getName() + ".")) {
				reply(sender, CountdownMessages.countdownNotFound(id));
				return;
			}

			reply(sender, CountdownMessages.countdownStopped(id));
		}
	}

	private final class ShutdownCommand extends LunaCommandBase {
		private static final String ROOT = "shutdown";

		ShutdownCommand() {
			super(SHUTDOWN_PERMISSION);
		}

		@Override
		public String getName() {
			return ROOT;
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/shutdown <time|cancel> [message]";
		}

		@Override
		public List<String> getTabCompletions(
			MinecraftServer server,
			ICommandSender sender,
			String[] args,
			net.minecraft.util.math.BlockPos pos
		) {
			if (args.length == 1) {
				return CommandCompletions.filterPrefix(Arrays.asList("cancel", "30s", "5m", "10m"), args[0]);
			}

			return new ArrayList<String>();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			if (shutdownTimer == null) {
				reply(sender, CountdownMessages.notReady());
				return;
			}

			if (args.length == 0) {
				reply(sender, CountdownMessages.shutdownUsage(ROOT));
				return;
			}

			if ("cancel".equalsIgnoreCase(args[0])) {
				if (!shutdownTimer.cancel("Huỷ bởi " + sender.getName() + ".")) {
					reply(sender, CountdownMessages.noShutdownScheduled());
				}

				return;
			}

			int seconds = parseSeconds(args[0]);

			if (seconds < 0) {
				reply(sender, CountdownMessages.invalidTime(args[0]));
				return;
			}

			if (!shutdownTimer.start(seconds, joinFrom(args, 1))) {
				reply(sender, CountdownMessages.shutdownAlreadyScheduled());
				return;
			}

			reply(sender, CountdownMessages.shutdownScheduled(
				Formatters.compactDuration(Duration.ofSeconds(seconds))
			));
		}
	}
}
