package dev.belikhun.luna.vault.backend;

import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.migration.DatabaseMigrator;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import dev.belikhun.luna.vault.api.LunaVaultApi;
import dev.belikhun.luna.vault.api.model.VaultDatabaseMigrations;
import dev.belikhun.luna.vault.backend.command.TransactionsCommand;
import dev.belikhun.luna.vault.backend.gui.TransactionHistoryGuiController;
import dev.belikhun.luna.vault.backend.placeholder.PaperVaultPlaceholderExpansion;
import dev.belikhun.luna.vault.backend.service.LunaVaultEconomyProvider;
import dev.belikhun.luna.vault.backend.service.PaperVaultGateway;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaVaultBackendPlugin extends JavaPlugin {
	private PaperVaultGateway gateway;
	private LunaLogger logger;
	private TransactionHistoryGuiController historyGui;
	private Economy economyProvider;
	private PaperVaultPlaceholderExpansion placeholderExpansion;

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore", "LunaCore chưa sẵn sàng. LunaVaultBackend sẽ tắt.")) {
			return;
		}

		saveDefaultConfig();
		logger = PaperPluginBootstrap.initLogger(this, "VaultBackend");
		ConfigStore coreConfig = LunaCore.services().configStore();
		Database database = LunaCore.services().databaseManager().getDatabase();
		try {
			DatabaseMigrator migrator = new DatabaseMigrator(database, logger.scope("Migration"));
			VaultDatabaseMigrations.register(migrator);
			migrator.migrateNamespace("lunavault");
		} catch (Exception exception) {
			logger.error("Không thể chuẩn bị schema cho LunaVaultBackend.", exception);
		}
		long timeoutMillis = getConfig().getLong("transport.timeout-millis", 3000L);
		int pageSize = getConfig().getInt("history.page-size", 45);
		gateway = new PaperVaultGateway(this, logger, LunaCore.services().pluginMessaging(), database, timeoutMillis);
		gateway.registerChannels();
		historyGui = new TransactionHistoryGuiController(this, gateway, coreConfig, pageSize);
		economyProvider = new LunaVaultEconomyProvider(this, gateway, coreConfig, timeoutMillis);

		getServer().getPluginManager().registerEvents(historyGui, this);
		getServer().getServicesManager().register(LunaVaultApi.class, gateway, this, ServicePriority.Normal);
		getServer().getServicesManager().register(Economy.class, economyProvider, this, ServicePriority.High);
		registerPlaceholderExpansion(coreConfig, timeoutMillis);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			TransactionsCommand command = new TransactionsCommand(historyGui);
			commands.registrar().register("transactions", command);
			commands.registrar().register("txns", command);
			commands.registrar().register("lichsu", command);
		});

		logger.success("LunaVaultBackend đã đăng ký Vault provider và gateway tới Velocity.");
	}

	@Override
	public void onDisable() {
		if (placeholderExpansion != null && placeholderExpansion.isRegistered()) {
			placeholderExpansion.unregister();
			placeholderExpansion = null;
		}
		if (gateway != null) {
			gateway.close();
		}
		if (getServer().getServicesManager() != null) {
			if (economyProvider != null) {
				getServer().getServicesManager().unregister(Economy.class, economyProvider);
			}
			if (gateway != null) {
				getServer().getServicesManager().unregister(LunaVaultApi.class, gateway);
			}
		}
	}

	private void registerPlaceholderExpansion(ConfigStore coreConfig, long timeoutMillis) {
		if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			logger.warn("PlaceholderAPI không hoạt động. Bỏ qua đăng ký namespace lunavault.");
			return;
		}

		try {
			placeholderExpansion = new PaperVaultPlaceholderExpansion(this, gateway, coreConfig, timeoutMillis);
			placeholderExpansion.register();
			logger.success("Đã đăng ký PlaceholderAPI namespace %lunavault_...%.");
		} catch (Throwable throwable) {
			placeholderExpansion = null;
			logger.error("Không thể đăng ký PlaceholderAPI expansion lunavault.", throwable);
		}
	}
}
