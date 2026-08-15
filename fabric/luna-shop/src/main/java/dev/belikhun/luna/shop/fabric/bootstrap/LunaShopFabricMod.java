package dev.belikhun.luna.shop.fabric.bootstrap;

import dev.belikhun.luna.core.api.config.YamlConfigFile;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.dependency.DependencyManager;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.fabric.LunaCoreFabric;
import dev.belikhun.luna.core.fabric.logging.FabricLunaLoggers;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
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
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * The shop, as a fabric mod.
 *
 * It needs three things from the rest of the fleet and refuses to start without
 * the first: LunaVault for the wallet, the core's database for the transaction
 * history (which degrades to "history off" rather than failing), and the core's
 * chat prompt service for the screens that ask a question.
 */
public final class LunaShopFabricMod implements DedicatedServerModInitializer {
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

	public LunaShopFabricMod() {
		this.logger = FabricLunaLoggers.create("LunaShop", true);
		this.commands = new ShopCommands(() -> runtime);
		this.guiController = null;
		this.runtime = null;
		this.pendingServer = null;
		this.ticksWaitingForDependencies = 0;
		this.startupAbandoned = false;
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> pendingServer = server);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(server -> startWhenDependenciesAreReady());

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> commands.register(dispatcher));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();

			if (guiController != null && player != null) {
				guiController.forget(player.getUUID());
			}
		});
	}

	/**
	 * Start once LunaVault has published its API.
	 *
	 * Fabric orders SERVER_STARTED listeners by mod id, and "lunashop" sorts
	 * before "lunavaultbackend" - so on that event the wallet does not exist yet,
	 * whatever the manifest declares; a declared dependency is not an ordering.
	 * Waiting costs nothing, because no player can be connected before the first
	 * tick; giving up after a few seconds is what turns a genuinely missing
	 * LunaVault into one line rather than one line per tick.
	 */
	private void startWhenDependenciesAreReady() {
		if (runtime != null || startupAbandoned || pendingServer == null || !LunaCoreFabric.isReady()) {
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
		DependencyManager dependencyManager = LunaCoreFabric.services().dependencyManager();
		YamlConfigFile coreConfig = LunaCoreFabric.services().config();
		Database database = LunaCoreFabric.services().database();

		LunaVaultApi vaultApi = dependencyManager.resolveOptional(LunaVaultApi.class).orElse(null);
		ChatPrompts chatPrompts = dependencyManager.resolveOptional(ChatPrompts.class).orElse(null);

		if (vaultApi == null || chatPrompts == null) {
			return false;
		}

		Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize().resolve(MOD_ID);
		YamlConfigFile config = YamlConfigFile.load(configDirectory.resolve("config.yml"), getClass(), CONFIG_RESOURCE);

		logger = FabricLunaLoggers.create("LunaShop", true, config.getBoolean("logging.debug", false));

		ShopTransactionStore transactionStore = new ShopTransactionStore(database, logger);
		migrateHistorySchema(database);

		ShopItemStore store = new ShopItemStore(server, configDirectory, logger);
		store.load();

		ShopEconomyService economy = new LunaVaultEconomyService(
			server,
			vaultApi,
			config.getLong("economy.timeout-millis", 3000L),
			coreConfig,
			logger
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

		logger.success("LunaShop Fabric đã sẵn sàng với " + store.all().size() + " mặt hàng.");
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

	private void onServerStopping(MinecraftServer server) {
		if (guiController != null) {
			guiController.close();
			guiController = null;
		}

		runtime = null;
		pendingServer = null;
		ticksWaitingForDependencies = 0;
		startupAbandoned = false;
	}
}
