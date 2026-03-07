package dev.belikhun.luna.smp;

import dev.belikhun.luna.core.LunaCore;
import dev.belikhun.luna.core.api.config.ConfigStore;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.string.MessageFormatter;
import dev.belikhun.luna.smp.farmprotect.FarmProtectListener;
import dev.belikhun.luna.smp.repair.ToolRepairCommand;
import dev.belikhun.luna.smp.repair.ToolRepairConfirmGui;
import dev.belikhun.luna.smp.repair.ToolRepairService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class LunaSmpPlugin extends JavaPlugin {
	private LunaLogger logger;
	private Economy economy;
	private ToolRepairService toolRepairService;
	private ToolRepairConfirmGui toolRepairConfirmGui;
	private ConfigStore configStore;
	private MessageFormatter messageFormatter;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng hoặc bật lỗi. LunaSmp sẽ tắt để tránh lỗi classpath.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		logger = LunaLogger.forPlugin(this, true).scope("Smp");
		logger.audit("Bắt đầu khởi tạo LunaSmp.");
		configStore = ConfigStore.of(this, "config.yml");
		messageFormatter = LunaCore.services().messageFormatter();

		if (!setupEconomy()) {
			logger.error("Không tìm thấy Vault Economy provider. LunaSmp sẽ tắt.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		toolRepairService = new ToolRepairService(configStore, economy);
		toolRepairConfirmGui = new ToolRepairConfirmGui(toolRepairService, messageFormatter, logger);
		registerPermissions();
		registerCommands();

		logger.success("Đã bật module sửa dụng cụ.");
		getServer().getPluginManager().registerEvents(toolRepairConfirmGui, this);

		getServer().getPluginManager().registerEvents(new FarmProtectListener(), this);
		logger.success("Đã bật luật Farm Protect.");

		logger.success("LunaSmp đã khởi động thành công.");
	}

	@Override
	public void onDisable() {
		if (logger != null) {
			logger.audit("LunaSmp đã tắt.");
		}
	}

	private boolean setupEconomy() {
		if (getServer().getPluginManager().getPlugin("Vault") == null) {
			return false;
		}

		RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
		if (registration == null) {
			return false;
		}

		economy = registration.getProvider();
		return economy != null;
	}

	private void registerPermissions() {
		Permission root = new Permission("lunasmp.*", PermissionDefault.OP);
		Permission repair = new Permission("lunasmp.repair", PermissionDefault.TRUE);
		repair.addParent(root, true);

		registerPermissionIfAbsent(root);
		registerPermissionIfAbsent(repair);
	}

	private void registerPermissionIfAbsent(Permission permission) {
		if (Bukkit.getPluginManager().getPermission(permission.getName()) != null) {
			return;
		}

		Bukkit.getPluginManager().addPermission(permission);
	}

	private void registerCommands() {
		ToolRepairCommand repairCommand = new ToolRepairCommand(toolRepairService, messageFormatter, toolRepairConfirmGui);

		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
			commands.registrar().register("repair", repairCommand);
			commands.registrar().register("fix", repairCommand);
		});
		logger.audit("Đã đăng ký lệnh sửa dụng cụ bằng Paper Command Lifecycle.");
	}
}
