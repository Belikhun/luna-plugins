package dev.belikhun.luna.shop;

import dev.belikhun.luna.shop.command.ShopAdminCommand;
import dev.belikhun.luna.shop.command.ShopCommand;
import dev.belikhun.luna.shop.economy.ShopEconomyService;
import dev.belikhun.luna.shop.economy.VaultEconomyService;
import dev.belikhun.luna.shop.gui.ShopGuiController;
import dev.belikhun.luna.shop.service.ShopService;
import dev.belikhun.luna.shop.store.ShopItemStore;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaShopPlugin extends JavaPlugin {
    private ShopItemStore itemStore;
    private ShopService shopService;
    private ShopGuiController guiController;

    @Override
    public void onEnable() {
        saveResource("items.yml", false);

        this.itemStore = new ShopItemStore(this);
        itemStore.load();

        ShopEconomyService economyService = VaultEconomyService.create(this).orElse(null);
        if (economyService == null) {
            getLogger().severe("Không tìm thấy Economy provider từ Vault. LunaShop sẽ tắt.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.shopService = new ShopService(economyService, itemStore);
        this.guiController = new ShopGuiController(this, shopService, itemStore);

        registerCommand("shop", new ShopCommand(guiController, itemStore));
        registerCommand("shopadmin", new ShopAdminCommand(this, itemStore, guiController));
        getLogger().info("LunaShop đã khởi động thành công với " + itemStore.all().size() + " mặt hàng.");
    }

    @Override
    public void onDisable() {
        if (itemStore != null) {
            itemStore.save();
        }
    }
}
