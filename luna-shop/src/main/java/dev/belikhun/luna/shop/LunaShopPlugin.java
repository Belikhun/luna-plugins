package dev.belikhun.luna.shop;

import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.shop.command.LunaShopReloadCommand;
import dev.belikhun.luna.shop.command.ShopAdminCommand;
import dev.belikhun.luna.shop.command.ShopCommand;
import dev.belikhun.luna.shop.economy.LunaVaultEconomyService;
import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.economy.VaultEconomyService;
import dev.belikhun.luna.shop.gui.ShopGuiController;
import dev.belikhun.luna.shop.migration.ShopTransactionHistoryMigration;
import dev.belikhun.luna.shop.service.ShopService;
import dev.belikhun.luna.shop.service.ShopTradeLimitService;
import dev.belikhun.luna.shop.service.ShopTransactionStore;
import dev.belikhun.luna.shop.store.ShopItemStore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.plugin.java.JavaPlugin;

public final class LunaShopPlugin extends JavaPlugin {
	private ShopItemStore itemStore;
	private ShopService shopService;
	private ShopGuiController guiController;
	private LunaLogger logger;

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore", "LunaCore chưa sẵn sàng hoặc bật lỗi. LunaShop sẽ tắt để tránh lỗi classpath.")) {
			return;
		}

		this.logger = PaperPluginBootstrap.initLogger(this, "Shop");
		logger.audit("Bắt đầu khởi tạo Luna Shop.");

		PaperPluginBootstrap.ensureDataFolder(this);
		PaperPluginBootstrap.ensureResourceFile(this, "items.yml", logger);

		this.itemStore = new ShopItemStore(this, logger.scope("Store"));
		itemStore.load();
		LunaCore.services().migrationManager().registerDatabaseMigration(new ShopTransactionHistoryMigration());
		ShopTransactionStore transactionStore = new ShopTransactionStore(
			LunaCore.services().databaseManager().getDatabase(),
			logger.scope("Transactions")
		);

		ShopEconomyService economyService = resolveEconomyService();
		if (economyService == null) {
			logger.error("Không tìm thấy LunaVault API hoặc Economy provider từ Vault. LunaShop sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		ShopTradeLimitService tradeLimitService = new ShopTradeLimitService(this);
		this.shopService = new ShopService(this, economyService, itemStore, tradeLimitService, transactionStore, logger.scope("Transactions"));
		this.guiController = new ShopGuiController(this, shopService, itemStore);

		ShopCommand shopCommand = new ShopCommand(guiController, itemStore);
		ShopAdminCommand shopAdminCommand = new ShopAdminCommand(this, itemStore, shopService, guiController);
		LunaShopReloadCommand reloadCommand = new LunaShopReloadCommand(this);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("shop", shopCommand);
			commands.registrar().register("buy", shopCommand);
			commands.registrar().register("store", shopCommand);
			commands.registrar().register("b", shopCommand);
			commands.registrar().register("shopadmin", shopAdminCommand);
			commands.registrar().register("lunashop", reloadCommand);
		});
		logger.success("LunaShop đã khởi động thành công với " + itemStore.all().size() + " mặt hàng.");
	}

	private ShopEconomyService resolveEconomyService() {
		try {
			ShopEconomyService directVault = LunaVaultEconomyService.create(this).orElse(null);
			if (directVault != null) {
				logger.audit("LunaShop đang dùng LunaVault API làm economy chính.");
				return directVault;
			}
		} catch (NoClassDefFoundError error) {
			logger.warn("Không thể nạp LunaVault API trực tiếp, chuyển sang Vault fallback: " + error.getMessage());
		}

		ShopEconomyService vaultFallback = VaultEconomyService.create(this).orElse(null);
		if (vaultFallback != null) {
			logger.audit("LunaShop đang dùng Vault economy làm fallback.");
		}
		return vaultFallback;
	}

	public void reloadShopModules() {
		LunaCore.services().configStore().reload();
		reloadConfig();
		if (itemStore != null) {
			itemStore.load();
		}
		logger.audit("Đã reload LunaCore config, LunaShop config và dữ liệu items.");
	}

	@Override
	public void onDisable() {
		if (itemStore != null) {
			itemStore.save();
		}

		if (logger != null) {
			logger.audit("LunaShop đã tắt.");
		}
	}
}

