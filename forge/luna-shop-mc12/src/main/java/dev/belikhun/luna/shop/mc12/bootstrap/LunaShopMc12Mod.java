package dev.belikhun.luna.shop.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.LunaCore;
import dev.belikhun.luna.core.mc12.logging.LegacyLunaLogger;
import dev.belikhun.luna.legacy.config.YamlConfigFile;
import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.shop.LunaVaultEconomyService;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.shop.ShopService;
import dev.belikhun.luna.legacy.shop.ShopTradeLimitService;
import dev.belikhun.luna.legacy.shop.ShopTransactionStore;
import dev.belikhun.luna.legacy.vault.LunaVaultApi;
import dev.belikhun.luna.shop.mc12.gui.ShopScreens;
import dev.belikhun.luna.shop.mc12.runtime.LegacyShopGameClock;
import dev.belikhun.luna.shop.mc12.runtime.LegacyShopInventory;
import dev.belikhun.luna.shop.mc12.runtime.LegacyShopItems;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;

import java.io.File;
import java.nio.file.Path;

/**
 * LunaShop on 1.12.2.
 *
 * Assembles the shared trunk against this line's three seams and hands the
 * result to the screens. Nothing about buying, selling or the daily limits is
 * decided here; that all lives in `luna-legacy-api` and is the same code every
 * other backend runs.
 *
 * **The economy is the vault, and the vault may not be there.** LunaVaultBackend
 * publishes `LunaVaultApi` from its own server-starting handler, and legacy FML
 * gives no ordering guarantee between two peer mods, so an absent api is a
 * refusal to start rather than a crash - a shop that cannot move money is worse
 * than no shop.
 */
@Mod(
	modid = LunaShopMc12Mod.MOD_ID,
	name = "LunaShop",
	version = "0.1.0-SNAPSHOT",
	acceptableRemoteVersions = "*",
	serverSideOnly = true,
	dependencies = "required-after:lunacore;after:lunavaultbackend"
)
public final class LunaShopMc12Mod {
	public static final String MOD_ID = "lunashop";

	/** How long a balance read may hold the server thread before it gives up. */
	private static final long ECONOMY_TIMEOUT_MS = 3000L;

	private LunaLogger logger;
	private Path configDir;
	private ShopScreens screens;

	@Mod.EventHandler
	public void onPreInit(FMLPreInitializationEvent event) {
		logger = LegacyLunaLogger.create(event.getModLog(), "LunaShop");

		File configRoot = event.getModConfigurationDirectory();

		configDir = configRoot.toPath().resolve(MOD_ID);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@Mod.EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		if (!LunaCore.isReady()) {
			logger.error("LunaCore chưa sẵn sàng. LunaShop (Forge 1.12.2) sẽ không khởi động.");

			return;
		}

		LunaVaultApi vaultApi = LunaCore.find(LunaVaultApi.class);

		if (vaultApi == null) {
			logger.error("Không tìm thấy LunaVaultApi. LunaShop cần LunaVaultBackend để xử lý tiền; sẽ không khởi động.");

			return;
		}

		final MinecraftServer server = event.getServer();
		PlayerBridge<EntityPlayerMP> players = playerBridge();

		if (players == null) {
			logger.error("Thiếu PlayerBridge từ LunaCore. LunaShop sẽ không khởi động.");

			return;
		}

		ShopItems<ItemStack> items = new LegacyShopItems();
		YamlConfigFile coreConfig = LunaCore.find(YamlConfigFile.class);

		ShopItemStore<ItemStack> store = new ShopItemStore<ItemStack>(items, configDir, logger);
		ShopTradeLimitService tradeLimits = new ShopTradeLimitService(new LegacyShopGameClock(server));
		ShopTransactionStore transactions = new ShopTransactionStore(LunaCore.find(Database.class), logger);

		LunaVaultEconomyService<EntityPlayerMP> economy = new LunaVaultEconomyService<EntityPlayerMP>(
			// the balance read is on the server thread during a click, so it must
			// never block there; the trade rules take a stale number over a stall
			() -> server.isCallingFromMinecraftThread(),
			players,
			vaultApi,
			ECONOMY_TIMEOUT_MS,
			coreConfig
		);

		ShopService<EntityPlayerMP, ItemStack> service = new ShopService<EntityPlayerMP, ItemStack>(
			items,
			new LegacyShopInventory(items),
			players,
			economy,
			store,
			tradeLimits,
			transactions,
			coreConfig,
			logger
		);

		screens = new ShopScreens(service, store, items, players);

		event.registerServerCommand(new ShopCommand(screens, store));
		event.registerServerCommand(new ShopAdminCommand(store, items));

		logger.success("LunaShop (Forge 1.12.2) đã sẵn sàng với " + store.all().size() + " mặt hàng.");
	}

	@Mod.EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		if (screens != null) {
			screens.close();
			screens = null;
		}

		logger.audit("LunaShop (Forge 1.12.2) đã dừng.");
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
		if (screens != null && event.player != null) {
			screens.forget(event.player.getUniqueID());
		}
	}

	@SuppressWarnings("unchecked")
	private PlayerBridge<EntityPlayerMP> playerBridge() {
		return (PlayerBridge<EntityPlayerMP>) LunaCore.find(PlayerBridge.class);
	}
}
