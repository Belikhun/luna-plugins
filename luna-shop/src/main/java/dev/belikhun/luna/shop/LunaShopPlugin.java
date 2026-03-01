package dev.belikhun.luna.shop;

import dev.belikhun.luna.core.LunaCore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.shop.command.LunaShopReloadCommand;
import dev.belikhun.luna.shop.command.ShopAdminCommand;
import dev.belikhun.luna.shop.command.ShopCommand;
import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.economy.VaultEconomyService;
import dev.belikhun.luna.shop.gui.ShopGuiController;
import dev.belikhun.luna.shop.migration.ShopTransactionHistoryMigration;
import dev.belikhun.luna.shop.service.ShopService;
import dev.belikhun.luna.shop.service.ShopTransactionStore;
import dev.belikhun.luna.shop.store.ShopItemStore;

import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaShopPlugin extends JavaPlugin {
    private ShopItemStore itemStore;
    private ShopService shopService;
    private ShopGuiController guiController;
    private LunaLogger logger;

    @Override
    public void onEnable() {
        if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
            getLogger().severe("LunaCore chưa sẵn sàng hoặc bật lỗi. LunaShop sẽ tắt để tránh lỗi classpath.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.logger = LunaLogger.forPlugin(this, true).scope("Shop");
        logger.audit("Bắt đầu khởi tạo Luna Shop.");

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File itemsFile = new File(getDataFolder(), "items.yml");
        if (!itemsFile.exists()) {
            saveResource("items.yml", false);
            logger.audit("Đã tạo items.yml mặc định.");
        }

        this.itemStore = new ShopItemStore(this, logger.scope("Store"));
        itemStore.load();
        LunaCore.services().migrationManager().registerDatabaseMigration(new ShopTransactionHistoryMigration());
        ShopTransactionStore transactionStore = new ShopTransactionStore(
            LunaCore.services().databaseManager().getDatabase(),
            logger.scope("Transactions")
        );

        ShopEconomyService economyService = VaultEconomyService.create(this).orElse(null);
        if (economyService == null) {
            logger.error("Không tìm thấy Economy provider từ Vault. LunaShop sẽ tắt.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.shopService = new ShopService(economyService, itemStore, transactionStore, logger.scope("Transactions"));
        this.guiController = new ShopGuiController(this, shopService, itemStore);

        ShopCommand shopCommand = new ShopCommand(guiController, itemStore);
        registerCommand("shop", shopCommand);
        registerCommand("buy", shopCommand);
        registerCommand("store", shopCommand);
        registerCommand("b", shopCommand);
        registerCommand("shopadmin", new ShopAdminCommand(this, itemStore, shopService, guiController));
        registerCommand("lunashop", new LunaShopReloadCommand(this));
        logger.success("LunaShop đã khởi động thành công với " + itemStore.all().size() + " mặt hàng.");
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
