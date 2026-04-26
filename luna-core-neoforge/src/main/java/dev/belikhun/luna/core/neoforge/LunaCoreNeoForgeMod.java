package dev.belikhun.luna.core.neoforge;

import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.AmqpMessagingConfig;
import dev.belikhun.luna.core.api.profile.LuckPermsService;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.logging.Logger;

@Mod(LunaCoreNeoForgeMod.MOD_ID)
public final class LunaCoreNeoForgeMod {
	public static final String MOD_ID = "lunacore";
	private static final String LUCKPERMS_MOD_ID = "luckperms";

	private final LunaLogger logger;
	private final DependencyManager dependencyManager;

	public LunaCoreNeoForgeMod() {
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaCoreNeoForge"), true).scope("CoreNeoForge");
		this.dependencyManager = new DependencyManager();
		NeoForge.EVENT_BUS.register(this);
		logger.audit("Đã đăng ký LunaCore NeoForge bootstrap.");
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		enforceRequiredDependencies();
		AmqpMessagingConfig amqpMessagingConfig = NeoForgeCoreConfigLoader.loadAmqpMessagingConfig(getClass(), logger);
		PermissionService permissionService = new LuckPermsService();
		dependencyManager.registerSingleton(MinecraftServer.class, server);
		dependencyManager.registerSingleton(DependencyManager.class, dependencyManager);
		dependencyManager.registerSingleton(LunaLogger.class, logger);
		dependencyManager.registerSingleton(AmqpMessagingConfig.class, amqpMessagingConfig);
		dependencyManager.registerSingleton(PermissionService.class, permissionService);
		LunaCoreNeoForge.set(new LunaCoreNeoForgeServices(MOD_ID, server, dependencyManager, logger));
		logger.success("LunaCore NeoForge đã khởi động bootstrap tối thiểu với LuckPerms permission service.");
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		dependencyManager.clear();
		LunaCoreNeoForge.clear();
		logger.audit("LunaCore NeoForge đã dọn dẹp bootstrap tối thiểu.");
	}

	private void enforceRequiredDependencies() {
		if (!ModList.get().isLoaded(LUCKPERMS_MOD_ID)) {
			throw new IllegalStateException("LunaCore NeoForge yêu cầu LuckPerms trên NeoForge backend. Hãy cài mod 'luckperms' trước khi khởi động Luna modules.");
		}
	}
}
