package dev.belikhun.luna.hat.forge.bootstrap;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.hat.mc.runtime.HatHooks;
import dev.belikhun.luna.hat.mc.runtime.HatService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /hat}: wear what you are holding, as the forge build.
 *
 * Everything it does lives in the shared trunk; this only starts it and hands the
 * mixin its service. The mixin is shared too - {@code ArmorSlot.mayPlace} has the
 * same shape on every version this fleet runs - though this line needs its own
 * config file, because mixin's compatibility level has to match the JVM forge
 * 1.20.1 runs on.
 */
@Mod(LunaHatForgeMod.MOD_ID)
public final class LunaHatForgeMod {
	public static final String MOD_ID = "lunahat";

	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";

	private final LunaLogger logger;
	private HatService hatService;

	public LunaHatForgeMod() {
		this.logger = LunaLoggers.create("LunaHat", true);
		this.hatService = null;

		MinecraftForge.EVENT_BUS.register(this);
	}

	/**
	 * Runs after LunaCore's own handler.
	 *
	 * A dependency's `ordering="AFTER"` in mods.toml orders mod *loading*, not
	 * the event bus: both handlers sit on the same bus at NORMAL and fire in
	 * registration order, which put this one ~90ms ahead of the core and left it
	 * without a permission service. LOWEST is what actually orders them, after the core and the messaging bus.
	 */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		PermissionService permissions = LunaCore.isReady()
			? LunaCore.services().dependencyManager().resolveOptional(PermissionService.class).orElse(null)
			: null;

		if (permissions == null) {
			logger.warn("Không tìm thấy permission service. Mọi người chơi đều được đội vật phẩm.");
		}

		hatService = new HatService(permissions);
		HatHooks.install(hatService);
		logger.success("LunaHat Forge đã sẵn sàng.");
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		HatHooks.clear();
		hatService = null;
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("hat").executes(context -> {
			CommandSourceStack source = context.getSource();

			if (!(source.getEntity() instanceof ServerPlayer player)) {
				source.sendSystemMessage(LunaTextComponents.mini(PLAYERS_ONLY));
				return 0;
			}

			HatService current = hatService;

			if (current == null) {
				return 0;
			}

			current.swapWithMainHand(player);
			return 1;
		}));
	}
}
