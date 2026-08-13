package dev.belikhun.luna.hat.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.hat.mc12.HatService;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.permission.PermissionService;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import java.util.List;

/**
 * `/hat` on 1.12.2.
 *
 * The first feature mod on this line, and so also the proof that the shape works:
 * a separate jar that finds luna-core's services at runtime rather than importing
 * them, degrades when the core is absent, and needs nothing from the core's own
 * jar but an interface.
 *
 * `dependencies` orders *loading*, which on legacy FML is also what orders the
 * `FMLServerStartingEvent` handlers - the core publishes its registry in its own
 * handler, and this one runs after. The null check stays anyway: a core that
 * failed to start is a working server with one mod missing, not a crash.
 */
@Mod(
	modid = LunaHatMc12Mod.MOD_ID,
	name = "LunaHat",
	version = "0.1.0-SNAPSHOT",
	dependencies = "required-after:lunacore",
	acceptableRemoteVersions = "*",
	serverSideOnly = true
)
public final class LunaHatMc12Mod {
	public static final String MOD_ID = "lunahat";

	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";

	private LunaLogger logger;
	private HatService hatService;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaHat");
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		PermissionService permissions = LunaCore.find(PermissionService.class);

		if (permissions == null) {
			logger.warn("Không tìm thấy permission service. Mọi người chơi đều được đội vật phẩm.");
		}

		hatService = new HatService(permissions);
		event.registerServerCommand(new HatCommand(hatService));

		logger.success("LunaHat (Forge 1.12.2) đã sẵn sàng.");
	}

	/** `/hat`, open to everyone; the item decides what permission applies. */
	private static final class HatCommand extends CommandBase {
		private final HatService hatService;

		HatCommand(HatService hatService) {
			this.hatService = hatService;
		}

		@Override
		public String getName() {
			return "hat";
		}

		@Override
		public String getUsage(ICommandSender sender) {
			return "/hat";
		}

		/**
		 * Zero, and a permission check that always passes: `CommandBase` defaults to
		 * requiring op and checks it before the command runs, which would put hat
		 * behind op for everybody. What may be worn is decided per item, inside the
		 * service.
		 */
		@Override
		public int getRequiredPermissionLevel() {
			return 0;
		}

		@Override
		public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
			return true;
		}

		@Override
		public List<String> getTabCompletions(
			MinecraftServer server,
			ICommandSender sender,
			String[] args,
			net.minecraft.util.math.BlockPos pos
		) {
			return java.util.Collections.emptyList();
		}

		@Override
		public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
			if (!(sender instanceof EntityPlayerMP)) {
				sender.sendMessage(LunaTextComponents.mini(PLAYERS_ONLY));
				return;
			}

			hatService.swapWithMainHand((EntityPlayerMP) sender);
		}
	}
}
