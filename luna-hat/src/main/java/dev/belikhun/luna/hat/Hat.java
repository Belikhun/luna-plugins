package dev.belikhun.luna.hat;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

public class Hat extends JavaPlugin {
	private LunaLogger logger;

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore", "LunaCore chưa sẵn sàng hoặc bật lỗi. LunaHat sẽ tắt để tránh lỗi classpath.")) {
			return;
		}

		logger = PaperPluginBootstrap.initLogger(this, "Hat");
		logger.audit("Bắt đầu khởi tạo LunaHat.");

		registerPermissions();

		HatHandler handler = new HatHandler();
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> commands.registrar().register("hat", handler));
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
		Permission blockPerm = new Permission("hat.blocks", PermissionDefault.TRUE);
		blockPerm.addParent(basePerm, true);
		Permission itemPerm = new Permission("hat.items", PermissionDefault.TRUE);
		itemPerm.addParent(basePerm, true);

		registerPermissionIfAbsent(basePerm);
		registerPermissionIfAbsent(blockPerm);
		registerPermissionIfAbsent(itemPerm);
	}

	private void registerPermissionIfAbsent(Permission permission) {
		if (Bukkit.getPluginManager().getPermission(permission.getName()) != null) {
			return;
		}

		Bukkit.getPluginManager().addPermission(permission);
	}
}

