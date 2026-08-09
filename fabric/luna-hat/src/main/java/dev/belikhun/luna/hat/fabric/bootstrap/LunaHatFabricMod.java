package dev.belikhun.luna.hat.fabric.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.hat.mc.runtime.HatHooks;
import dev.belikhun.luna.hat.mc.runtime.HatService;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /hat}: wear what you are holding.
 *
 * The command is half of it; the other half is the helmet slot accepting an item
 * the game would refuse, which {@code ArmorSlotMixin} handles and this mod turns
 * on by installing the service the mixin asks.
 */
public final class LunaHatFabricMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "lunahat";
	private static final String PLAYERS_ONLY = "<red>❌ Chỉ người chơi mới dùng lệnh này.</red>";

	private final LunaLogger logger;
	private HatService hatService;

	public LunaHatFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaHat", true);
		this.hatService = null;
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommand(dispatcher));
	}

	private void onServerStarted(MinecraftServer server) {
		PermissionService permissions = LunaCoreFabric.isReady()
			? LunaCoreFabric.services().dependencyManager().resolveOptional(PermissionService.class).orElse(null)
			: null;

		if (permissions == null) {
			logger.warn("Không tìm thấy permission service. Mọi người chơi đều được đội vật phẩm.");
		}

		hatService = new HatService(permissions);
		HatHooks.install(hatService);
		logger.success("LunaHat Fabric đã sẵn sàng.");
	}

	private void onServerStopping(MinecraftServer server) {
		HatHooks.clear();
		hatService = null;
	}

	private void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hat").executes(context -> {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				context.getSource().sendSystemMessage(LunaTextComponents.mini(PLAYERS_ONLY));
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
