package dev.belikhun.luna.shop.forge.bootstrap;

import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import dev.belikhun.luna.core.mc.LunaCore;
import dev.belikhun.luna.core.mc.logging.LunaLoggers;
import dev.belikhun.luna.shop.api.ShopTransactionHistoryMigration;
import dev.belikhun.luna.shop.api.ShopTransactionStore;
import dev.belikhun.luna.shop.mc.command.ShopCommands;
import dev.belikhun.luna.shop.mc.economy.LunaVaultEconomyService;
import dev.belikhun.luna.shop.mc.economy.ShopEconomyService;
import dev.belikhun.luna.shop.mc.gui.ShopGuiController;
import dev.belikhun.luna.shop.mc.service.ShopService;
import dev.belikhun.luna.shop.mc.service.ShopTradeLimitService;
import dev.belikhun.luna.shop.mc.store.ShopItemStore;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;

import java.nio.file.Path;

/**
 * The shop, as a Forge mod.
 *
 * Everything it does is the shared trunk; this is the entry point and the event
 * wiring. The deferred start mirrors the fabric bootstrap for the same reason:
 * a started event says nothing about whether LunaVault has published its API
 * yet, whatever the manifest declares, so the shop waits for the tick where the
 * wallet actually resolves.
 */
@Mod(LunaShopForgeMod.MOD_ID)
public final class LunaShopForgeMod {
	/** This mod's own default config inside its jar; see the note on the name. */
	private static final String CONFIG_RESOURCE = "lunashop/config.yml";

	public static final String MOD_ID = "lunashop";

	/** Five seconds at a healthy tick rate; long enough for a slow wallet, short enough to report. */
	private static final int STARTUP_GRACE_TICKS = 100;

	private final ShopCommands commands;
	private LunaLogger logger;
	private ShopGuiController guiController;
	private ShopCommands.ShopRuntime runtime;
	private MinecraftServer pendingServer;
	private int ticksWaitingForDependencies;
	private boolean startupAbandoned;

	public LunaShopForgeMod() {
		this.logger = LunaLoggers.create("LunaShop", true);
		this.commands = new ShopCommands(() -> runtime);
		this.guiController = null;
		this.runtime = null;
		this.pendingServer = null;
		this.ticksWaitingForDependencies = 0;
		this.startupAbandoned = false;
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		pendingServer = event.getServer();
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		// 1.20.1 fires one event with a phase rather than the Pre/Post pair
		// neoforge split it into; END is the same moment as Post.
		if (event.phase != TickEvent.Phase.END) {
			return;
		}

		if (runtime != null || startupAbandoned || pendingServer == null || !LunaCore.isReady()) {
			return;
		}

		if (start(pendingServer)) {
			pendingServer = null;
			return;
		}

		ticksWaitingForDependencies++;

		if (ticksWaitingForDependencies < STARTUP_GRACE_TICKS) {
			return;
		}

		startupAbandoned = true;
		pendingServer = null;
		logger.error("Không tìm thấy LunaVault sau " + STARTUP_GRACE_TICKS + " tick. LunaShop sẽ không khởi động.");
	}

	/** @return whether everything it needs was there and the shop is now running */
	private boolean start(MinecraftServer server) {
		DependencyManager dependencyManager = LunaCore.services().dependencyManager();
		YamlConfigFile coreConfig = LunaCore.services().config();
		Database database = LunaCore.services().database();

		LunaVaultApi vaultApi = dependencyManager.resolveOptional(LunaVaultApi.class).orElse(null);
		ChatPrompts chatPrompts = dependencyManager.resolveOptional(ChatPrompts.class).orElse(null);

		if (vaultApi == null || chatPrompts == null) {
			return false;
		}

		Path configDirectory = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
		YamlConfigFile config = YamlConfigFile.load(configDirectory.resolve("config.yml"), getClass(), CONFIG_RESOURCE);

		logger = LunaLoggers.create("LunaShop", true, config.getBoolean("logging.debug", false));

		ShopTransactionStore transactionStore = new ShopTransactionStore(database, logger);
		migrateHistorySchema(database);

		ShopItemStore store = new ShopItemStore(server, configDirectory, logger);
		store.load();

		ShopEconomyService economy = new LunaVaultEconomyService(
			server,
			vaultApi,
			config.getLong("economy.timeout-millis", 3000L),
			coreConfig
		);

		ShopService service = new ShopService(
			server,
			economy,
			store,
			new ShopTradeLimitService(server),
			transactionStore,
			coreConfig,
			logger
		);

		guiController = new ShopGuiController(server, service, store, chatPrompts);

		runtime = new ShopCommands.ShopRuntime(
			service,
			store,
			guiController,
			dependencyManager.resolveOptional(PermissionService.class).orElse(null)
		);

		dependencyManager.registerSingleton(ShopService.class, service);
		dependencyManager.registerSingleton(ShopItemStore.class, store);

		logger.success("LunaShop Forge đã sẵn sàng với " + store.all().size() + " mặt hàng.");
		return true;
	}

	/**
	 * The history table is this mod's own, so it creates it. Without a database
	 * the store already answers "off" and every screen says so.
	 */
	private void migrateHistorySchema(Database database) {
		if (database instanceof NoopDatabase) {
			logger.warn("Database chưa bật. Lịch sử giao dịch của LunaShop sẽ không được ghi.");
			return;
		}

		try {
			DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));
			migrator.register(new ShopTransactionHistoryMigration());
			migrator.migrateNamespace("luna_shop");
		} catch (Exception exception) {
			logger.error("Không thể chuẩn bị schema lịch sử giao dịch cho LunaShop.", exception);
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (guiController != null) {
			guiController.close();
			guiController = null;
		}

		runtime = null;
		pendingServer = null;
		ticksWaitingForDependencies = 0;
		startupAbandoned = false;
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (guiController != null && event.getEntity() instanceof ServerPlayer player) {
			guiController.forget(player.getUUID());
		}
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		commands.register(event.getDispatcher());
	}
}
