package dev.belikhun.luna.countdown;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.ui.LunaUi;

import dev.belikhun.luna.countdown.commands.CountdownCommand;
import dev.belikhun.luna.countdown.commands.ShutdownCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class Countdown extends JavaPlugin {
	private static final MiniMessage MINI = MiniMessage.miniMessage();
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

	public static Countdown instance;

	public LunaLogger logger;

	@Override
	public void onEnable() {
		if (!getServer().getPluginManager().isPluginEnabled("LunaCore")) {
			getLogger().severe("LunaCore chưa sẵn sàng hoặc bật lỗi. LunaCountdown sẽ tắt để tránh lỗi classpath.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		instance = this;
		logger = LunaLogger.forPlugin(this, true).scope("Countdown");
		logger.audit("Bắt đầu khởi tạo LunaCountdown.");

		// Register player join/quit events.
		Bukkit.getServer().getPluginManager().registerEvents(new PlayerEvent(), this);

		PluginCommand countdown = getCommand("countdown");
		if (countdown != null) {
			CountdownCommand countdownCommand = new CountdownCommand();
			countdown.setExecutor(countdownCommand);
			countdown.setTabCompleter(countdownCommand);
		}

		PluginCommand shutdown = getCommand("shutdown");
		if (shutdown != null) {
			ShutdownCommand shutdownCommand = new ShutdownCommand();
			shutdown.setExecutor(shutdownCommand);
			shutdown.setTabCompleter(shutdownCommand);
		}

		logger.success("LunaCountdown đã khởi động thành công.");
	}
	
	@Override
	public void onDisable() {
		if (ShutdownCommand.instance != null)
			ShutdownCommand.instance.cancel();

		CountdownCommand.stopAll();
		if (logger != null) {
			logger.audit("LunaCountdown đã tắt.");
		}
	}

	public static Component mm(String message) {
		return LunaUi.mini(message == null ? "" : message);
	}

	public static String legacy(String message) {
		return LEGACY.serialize(mm(message));
	}

	public static void broadcast(String message) {
		Bukkit.broadcast(mm(message));
	}

	public static String escape(String value) {
		return MINI.escapeTags(value == null ? "" : value);
	}

	public static int parseTime(String input) {
		try {
			if (input == null || input.isBlank()) {
				return -1;
			}

			String value = input.trim().toLowerCase(Locale.ROOT);
			if (value.endsWith("d")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 86400;
			} else if (value.endsWith("h")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 3600;
			} else if (value.endsWith("m")) {
				return Integer.parseInt(value.substring(0, value.length() - 1)) * 60;
			} else if (value.endsWith("s")) {
				return Integer.parseInt(value.substring(0, value.length() - 1));
			} else {
				return Integer.parseInt(value);
			}
		} catch(NumberFormatException e) {
			return -1;
		}
	}
}
