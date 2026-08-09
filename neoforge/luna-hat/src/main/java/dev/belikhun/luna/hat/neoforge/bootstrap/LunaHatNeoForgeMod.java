package dev.belikhun.luna.hat.neoforge.bootstrap;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.neoforge.LunaCoreNeoForge;
import dev.belikhun.luna.core.neoforge.logging.NeoForgeLunaLoggers;
import dev.belikhun.luna.hat.mc.runtime.HatHooks;
import dev.belikhun.luna.hat.mc.runtime.HatService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * {@code /hat}: wear what you are holding, as the NeoForge build.
 *
 * Everything it does lives in the shared trunk; this only starts it and hands the
 * mixin its service. The mixin itself is shared too - {@code ArmorSlot.mayPlace}
 * has the same shape on every version this fleet runs.
 */
@Mod(LunaHatNeoForgeMod.MOD_ID)
public final class LunaHatNeoForgeMod {
	public static final String MOD_ID = "lunahat";
	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";

	private final LunaLogger logger;
	private HatService hatService;

	public LunaHatNeoForgeMod(IEventBus modEventBus) {
		this.logger = NeoForgeLunaLoggers.create("LunaHat", true);
		this.hatService = null;
		NeoForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		PermissionService permissions = LunaCoreNeoForge.isReady()
			? LunaCoreNeoForge.services().dependencyManager().resolveOptional(PermissionService.class).orElse(null)
			: null;

		if (permissions == null) {
			logger.warn("Không tìm thấy permission service. Mọi người chơi đều được đội vật phẩm.");
		}

		hatService = new HatService(permissions);
		HatHooks.install(hatService);
		logger.success("LunaHat NeoForge đã sẵn sàng.");
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
