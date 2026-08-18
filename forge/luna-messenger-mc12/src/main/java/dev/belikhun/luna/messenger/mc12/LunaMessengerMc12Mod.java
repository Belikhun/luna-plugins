package dev.belikhun.luna.messenger.mc12;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.messenger.MessengerCommandType;
import dev.belikhun.luna.legacy.messenger.MessengerMessages;
import dev.belikhun.luna.legacy.messenger.runtime.MessengerRuntime;
import dev.belikhun.luna.legacy.messenger.runtime.MessengerRuntimeFactory;
import dev.belikhun.luna.legacy.messenger.runtime.PlayerAudience;
import dev.belikhun.luna.legacy.string.CommandCompletions;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * `/msg`, `/r`, `/poke`, `/nw` and `/sv` on 1.12.2, plus chat relayed across the
 * network.
 *
 * The runtime underneath is shared with every other platform; what is different
 * here is the command layer. Brigadier arrived in 1.13, so the trees the modern
 * builds declare are hand-parsed `CommandBase` subclasses, and tab completion is
 * the runtime's own suggestion list filtered by prefix rather than a
 * `SuggestionProvider`.
 *
 * **Vanilla's `/tell` is replaced rather than merged.** 1.12.2 keeps commands in a
 * name-to-command map, so registering ours last wins outright - which is what we
 * want, and simpler than the brigadier builds, where a same-named literal merges
 * with vanilla's and its `<targets>` argument still matches first.
 */
@Mod(
	modid = LunaMessengerMc12Mod.MOD_ID,
	name = "LunaMessenger",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore;required-after:lunacoremessaging",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaMessengerMc12Mod {
	public static final String MOD_ID = "lunamessenger";

	private LunaLogger logger;
	private MessengerRuntime<EntityPlayerMP> runtime;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaMessenger");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaMessenger sẽ không khởi động.");
			return;
		}

		@SuppressWarnings("unchecked")
		PlayerBridge<EntityPlayerMP> players = (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);

		if (players == null) {
			logger.error("Thiếu PlayerBridge từ LunaCore. LunaMessenger sẽ không khởi động.");
			return;
		}

		PlayerAudience<EntityPlayerMP> audience = new LegacyPlayerAudience();

		runtime = MessengerRuntimeFactory.create(logger, players, audience, LunaCore.services());

		LunaCore.services().register(MessengerRuntime.class, runtime);
		MinecraftForge.EVENT_BUS.register(this);

		event.registerServerCommand(new ContextCommand("nw", MessengerCommandType.SWITCH_NETWORK,
			MessengerMessages.networkSwitchFailed()));
		event.registerServerCommand(new ContextCommand("sv", MessengerCommandType.SWITCH_SERVER,
			MessengerMessages.serverSwitchFailed()));
		event.registerServerCommand(new PokeCommand());

		for (String alias : MessengerMessages.DIRECT_COMMANDS) {
			event.registerServerCommand(new DirectCommand(alias));
		}

		for (String alias : MessengerMessages.REPLY_COMMANDS) {
			event.registerServerCommand(new ReplyCommand(alias));
		}

		logger.success("LunaMessenger (Forge 1.12.2) đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (runtime != null && event.player instanceof EntityPlayerMP) {
			runtime.publishJoin((EntityPlayerMP) event.player, false);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (runtime != null && event.player instanceof EntityPlayerMP) {
			runtime.publishLeave((EntityPlayerMP) event.player);
		}
	}

	/**
	 * Chat leaves this server and comes back through the proxy.
	 *
	 * Cancelling is what makes it cross-server rather than duplicated: the message
	 * is handed to the messenger and the proxy decides who sees it, so letting
	 * vanilla also broadcast it locally would show it twice to everyone here.
	 */
	@SubscribeEvent
	public void onServerChat(ServerChatEvent event) {
		if (runtime == null) {
			return;
		}

		EntityPlayerMP player = event.getPlayer();
		String message = event.getMessage();

		if (player == null || Strings.isBlank(message)) {
			return;
		}

		event.setCanceled(true);

		if (!runtime.sendCommand(player, MessengerCommandType.SEND_CHAT, message.trim())) {
			player.sendMessage(LunaTextComponents.mini(MessengerMessages.chatFailed()));
		}
	}

	/**
	 * A death leaves this server for the proxy, which announces it on Discord.
	 *
	 * The sentence vanilla already built is what travels: it is assembled from the
	 * combat tracker's record of what killed the player, including a mod's own
	 * damage source, and the proxy can see none of that.
	 *
	 * Nothing is cancelled and the local announcement is left alone, unlike chat
	 * above. Chat is cancelled because the proxy re-broadcasts it to every server;
	 * a death belongs to the server it happened on, and suppressing it here would
	 * take it away from the players who were standing there.
	 */
	@SubscribeEvent
	public void onLivingDeath(LivingDeathEvent event) {
		if (runtime == null) {
			return;
		}

		if (!(event.getEntityLiving() instanceof EntityPlayerMP)) {
			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) event.getEntityLiving();

		// the gamerule is the operator saying they do not want death messages at
		// all; announcing to Discord anyway would route around that
		if (player.world == null || !player.world.getGameRules().getBoolean("showDeathMessages")) {
			return;
		}

		ITextComponent deathMessage = player.getCombatTracker().getDeathMessage();

		if (deathMessage == null) {
			return;
		}

		String rendered = deathMessage.getUnformattedText().trim();

		if (Strings.isBlank(rendered)) {
			return;
		}

		runtime.sendCommand(player, MessengerCommandType.SEND_DEATH, rendered);
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (LunaCore.isReady()) {
			LunaCore.services().unregister(MessengerRuntime.class);
		}

		if (runtime != null) {
			runtime.close();
			runtime = null;
		}

		logger.audit("LunaMessenger (Forge 1.12.2) đã dừng.");
	}

	private static EntityPlayerMP playerOf(ICommandSender sender) {
		return sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
	}

	private static void reply(ICommandSender sender, String miniMessage) {
		sender.sendMessage(LunaTextComponents.mini(miniMessage));
	}

	/** Everything after `args[index]`, rejoined; null when there is nothing there. */
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
	 * A messenger command.
	 *
	 * `getRequiredPermissionLevel` is 0 because these are for everyone; the base
	 * class defaults to requiring op, which would put `/msg` behind it.
	 */
	private abstract class MessengerCommandBase extends CommandBase {
		private final String name;

		MessengerCommandBase(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public int getRequiredPermissionLevel() {
			return 0;
		}

		@Override
		public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
			return true;
		}

		/** The player names the runtime believes are reachable, filtered by prefix. */
		final List<String> suggestTargets(ICommandSender sender, String partial) {
			if (runtime == null) {
				return new ArrayList<String>();
			}

			Collection<String> targets = runtime.suggestDirectTargets(partial, sender.getName());

			return CommandCompletions.filterPrefix(new ArrayList<String>(targets), partial);
		}

		/** Whether the messenger is up; tells the sender when it is not. */
		final boolean ready(ICommandSender sender) {
			if (runtime != null) {
				return true;
			}

			reply(sender, MessengerMessages.notReady());

			return false;
		}

		/** The sender as a player; tells them when the console tries to use this. */
		final EntityPlayerMP requirePlayer(ICommandSender sender) {
			EntityPlayerMP player = playerOf(sender);

			if (player == null) {
				reply(sender, MessengerMessages.notAPlayer());
			}

			return player;
		}
	}

	/** `/nw` and `/sv`: move the sender's chat context, with no argument. */
	private final class ContextCommand extends MessengerCommandBase {
		private final MessengerCommandType commandType;
		private final String failureMessage;

		ContextCommand(String name, MessengerCommandType commandType, String failureMessage) {
			super(name);

			this.commandType = commandType;
			this.failureMessage = failureMessage;
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/" + getName();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			EntityPlayerMP player = requirePlayer(sender);

			if (player == null || !ready(sender)) {
				return;
			}

			if (!runtime.sendCommand(player, commandType, "")) {
				reply(sender, failureMessage);
			}
		}
	}

	/** `/poke <player>`. */
	private final class PokeCommand extends MessengerCommandBase {
		PokeCommand() {
			super("poke");
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/poke <player>";
		}

		@Override
		public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
			return args.length == 1 ? suggestTargets(sender, args[0]) : new ArrayList<String>();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			EntityPlayerMP player = requirePlayer(sender);

			if (player == null || !ready(sender)) {
				return;
			}

			if (args.length < 1) {
				reply(sender, MessengerMessages.pokeUsage());
				return;
			}

			if (!runtime.sendCommand(player, MessengerCommandType.SEND_POKE, args[0], args[0])) {
				reply(sender, MessengerMessages.pokeFailed());
			}
		}
	}

	/**
	 * `/msg <player> [message]`.
	 *
	 * With a message it sends one; with only a target it switches the sender's
	 * chat context to that player, which is the same two-in-one the modern builds
	 * express as two brigadier branches.
	 */
	private final class DirectCommand extends MessengerCommandBase {
		DirectCommand(String name) {
			super(name);
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/" + getName() + " <player> [message]";
		}

		@Override
		public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
			return args.length == 1 ? suggestTargets(sender, args[0]) : new ArrayList<String>();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			EntityPlayerMP player = requirePlayer(sender);

			if (player == null || !ready(sender)) {
				return;
			}

			if (args.length < 1) {
				reply(sender, MessengerMessages.directUsage());
				return;
			}

			String target = args[0];
			String message = joinFrom(args, 1);

			if (message == null) {
				if (!runtime.sendCommand(player, MessengerCommandType.SWITCH_DIRECT, target, target)) {
					reply(sender, MessengerMessages.directSwitchFailed());
				}

				return;
			}

			if (!runtime.sendCommand(player, MessengerCommandType.SEND_DIRECT, message, target)) {
				reply(sender, MessengerMessages.directFailed());
			}
		}
	}

	/** `/r <message>`. */
	private final class ReplyCommand extends MessengerCommandBase {
		ReplyCommand(String name) {
			super(name);
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/" + getName() + " <message>";
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			EntityPlayerMP player = requirePlayer(sender);

			if (player == null || !ready(sender)) {
				return;
			}

			String message = joinFrom(args, 0);

			if (message == null) {
				reply(sender, MessengerMessages.replyUsage());
				return;
			}

			if (!runtime.sendCommand(player, MessengerCommandType.SEND_REPLY, message)) {
				reply(sender, MessengerMessages.replyFailed());
			}
		}
	}

	/** Aliases the runtime's own command list declares, kept beside it for clarity. */
	static List<String> aliases() {
		List<String> all = new ArrayList<String>();

		all.addAll(Arrays.asList(MessengerMessages.DIRECT_COMMANDS));
		all.addAll(Arrays.asList(MessengerMessages.REPLY_COMMANDS));

		return all;
	}
}
