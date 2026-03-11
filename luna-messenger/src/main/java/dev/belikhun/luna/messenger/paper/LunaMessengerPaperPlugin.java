package dev.belikhun.luna.messenger.paper;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.messenger.paper.command.MessengerContextCommand;
import dev.belikhun.luna.messenger.paper.listener.PaperChatCaptureListener;
import dev.belikhun.luna.messenger.paper.listener.PaperJoinLeaveSuppressListener;
import dev.belikhun.luna.messenger.paper.service.PaperBackendPlaceholderResolver;
import dev.belikhun.luna.messenger.paper.service.PaperMessengerGateway;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LunaMessengerPaperPlugin extends JavaPlugin {
	private LunaLogger logger;
	private PluginMessageBus<Player, Player> pluginMessaging;
	private PaperMessengerGateway gateway;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng hoặc bật lỗi. LunaMessenger sẽ tắt để tránh lỗi classpath.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		saveDefaultConfig();

		logger = LunaLogger.forPlugin(this, true).scope("MessengerPaper");
		pluginMessaging = LunaCore.services().pluginMessaging();
		boolean timeoutEnabled = getConfig().getBoolean("request.timeout.enabled", true);
		long timeoutMillis = getConfig().getLong("request.timeout.millis", 6000L);
		long timeoutCheckIntervalTicks = getConfig().getLong("request.timeout.check-interval-ticks", 20L);
		gateway = new PaperMessengerGateway(
			this,
			logger,
			pluginMessaging,
			new PaperBackendPlaceholderResolver(this, getConfig().getStringList("placeholder-api.export-keys")),
			timeoutMillis,
			timeoutCheckIntervalTicks,
			timeoutEnabled
		);
		gateway.registerChannels();

		bindCommand("nw", new MessengerContextCommand(gateway, MessengerContextCommand.ContextType.NETWORK));
		bindCommand("sv", new MessengerContextCommand(gateway, MessengerContextCommand.ContextType.SERVER));
		bindCommand("msg", new MessengerContextCommand(gateway, MessengerContextCommand.ContextType.DIRECT));
		bindCommand("r", new MessengerContextCommand(gateway, MessengerContextCommand.ContextType.REPLY));
		getServer().getPluginManager().registerEvents(new PaperChatCaptureListener(gateway), this);
		getServer().getPluginManager().registerEvents(new PaperJoinLeaveSuppressListener(), this);

		logger.success("LunaMessenger (Paper) đã khởi động thành công.");
	}

	@Override
	public void onDisable() {
		if (gateway != null) {
			gateway.close();
		}
		if (logger != null) {
			logger.audit("LunaMessenger (Paper) đã tắt.");
		}
	}

	private void bindCommand(String name, MessengerContextCommand command) {
		registerCommand(name, command);
	}
}
