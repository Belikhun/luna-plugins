package dev.belikhun.luna.core.api.string;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

public final class MessageFormatter {
	private final LunaLogger logger;
	private final MiniMessage miniMessage;
	private final org.bukkit.plugin.Plugin plugin;

	public MessageFormatter(org.bukkit.plugin.Plugin plugin, LunaLogger logger) {
		this.plugin = plugin;
		this.logger = logger.scope("Formatter");
		this.miniMessage = MiniMessage.miniMessage();
	}

	public Component format(CommandSender sender, String message) {
		return format(sender, message, Collections.emptyMap());
	}

	public Component format(CommandSender sender, String message, Map<String, String> placeholders) {
		String output = applyInternalPlaceholders(message, placeholders);
		output = applyPlaceholderApi(sender, output);
		return miniMessage.deserialize(output);
	}

	public String formatString(CommandSender sender, String message, Map<String, String> placeholders) {
		String output = applyInternalPlaceholders(message, placeholders);
		return applyPlaceholderApi(sender, output);
	}

	private String applyInternalPlaceholders(String message, Map<String, String> placeholders) {
		String output = message;
		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			output = output.replace("{" + entry.getKey() + "}", entry.getValue());
		}
		return output;
	}

	private String applyPlaceholderApi(CommandSender sender, String message) {
		if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
			return message;
		}

		try {
			Class<?> placeholderClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
			Method method = placeholderClass.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
			if (sender instanceof org.bukkit.OfflinePlayer player) {
				Object value = method.invoke(null, player, message);
				return value == null ? message : String.valueOf(value);
			}
		} catch (ReflectiveOperationException exception) {
			logger.warn("Không thể áp dụng PlaceholderAPI: " + exception.getMessage());
		}

		return message;
	}
}
