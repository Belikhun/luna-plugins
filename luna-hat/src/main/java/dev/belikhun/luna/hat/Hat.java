package dev.belikhun.luna.hat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

public class Hat extends JavaPlugin {
	private LunaLogger logger;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng hoặc bật lỗi. LunaHat sẽ tắt để tránh lỗi classpath.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		logger = LunaLogger.forPlugin(this, true).scope("Hat");
		logger.audit("Bắt đầu khởi tạo LunaHat.");

		registerPermissions();

		HatHandler handler = new HatHandler();

		if (this.getCommand("hat") != null) {
			this.getCommand("hat").setExecutor(handler);
		}
		this.getServer().getPluginManager().registerEvents(handler, this);

		logger.success("LunaHat đã khởi động thành công.");
	}

	@Override
	public void onDisable() {
		if (logger != null) {
			logger.audit("LunaHat đã tắt.");
		}
	}

	private void registerPermissions() {
		Permission basePerm = new Permission("hat.*", PermissionDefault.OP);
		Bukkit.getPluginManager().addPermission(basePerm);

		Permission blockPerm = new Permission("hat.blocks", PermissionDefault.TRUE);
		blockPerm.addParent(basePerm, true);
		Bukkit.getPluginManager().addPermission(blockPerm);

		Permission itemPerm = new Permission("hat.items", PermissionDefault.TRUE);
		itemPerm.addParent(basePerm, true);
		Bukkit.getPluginManager().addPermission(itemPerm);

		Material[] materials = Material.values();
		for (Material mat : materials) {
			Permission perm = new Permission("hat." + mat.name(), PermissionDefault.TRUE);
			if (mat.isBlock()) {
				perm.addParent(blockPerm, true);
			} else {
				perm.addParent(itemPerm, true);
			}
			Bukkit.getPluginManager().addPermission(perm);
		}
	}
}
