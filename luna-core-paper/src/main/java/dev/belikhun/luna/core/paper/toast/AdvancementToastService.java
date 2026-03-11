package dev.belikhun.luna.core.paper.toast;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdvancementToastService implements ToastService {
	private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private final JavaPlugin plugin;

	public AdvancementToastService(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public ToastResult sendOneShot(Player player, String keyPrefix, Component title, Component subtitle) {
		if (plugin == null || player == null) {
			return ToastResult.fail("Plugin/player is null");
		}

		Component titleComponent = title == null ? Component.empty() : title;
		Component subtitleComponent = subtitle == null ? Component.empty() : subtitle;
		String titleText = PLAIN.serialize(titleComponent).trim();
		String subtitleText = PLAIN.serialize(subtitleComponent).trim();
		Component toastComponent = subtitleText.isBlank() ? titleComponent : subtitleComponent;
		if (subtitleText.isBlank() && titleText.isBlank()) {
			toastComponent = Component.text("Bạn được nhắc đến");
		}

		String prefix = (keyPrefix == null || keyPrefix.isBlank()) ? "toast" : keyPrefix;
		String keyName = prefix + "_" + player.getUniqueId().toString().replace("-", "") + "_" + Long.toHexString(System.nanoTime());
		NamespacedKey key = new NamespacedKey(plugin, keyName);

		try {
			loadToastAdvancement(key, toastComponent);
			Advancement advancement = Bukkit.getAdvancement(key);
			if (advancement == null) {
				removeAdvancement(key);
				return ToastResult.fail("Advancement not found after load: " + key);
			}

			if (!player.isOnline()) {
				removeAdvancement(key);
				return ToastResult.fail("Player offline before toast: " + player.getName());
			}

			try {
				player.getAdvancementProgress(advancement).revokeCriteria("trigger");
			} catch (Exception ignored) {
			}

			player.getAdvancementProgress(advancement).awardCriteria("trigger");
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				try {
					if (player.isOnline()) {
						player.getAdvancementProgress(advancement).revokeCriteria("trigger");
					}
				} catch (Exception ignored) {
				}
				removeAdvancement(key);
			}, 40L);

			return ToastResult.ok();
		} catch (Throwable throwable) {
			removeAdvancement(key);
			String reason = throwable.getClass().getSimpleName();
			String message = throwable.getMessage();
			if (message != null && !message.isBlank()) {
				reason += ": " + message;
			}
			return ToastResult.fail(reason + " key=" + key);
		}
	}

	@SuppressWarnings("deprecation")
	private void loadToastAdvancement(NamespacedKey key, Component message) {
		String messageJson = GSON.serialize(message == null ? Component.empty() : message);
		String advancementJson = "{\n"
			+ "  \"criteria\": {\n"
			+ "    \"trigger\": {\n"
			+ "      \"trigger\": \"minecraft:impossible\"\n"
			+ "    }\n"
			+ "  },\n"
			+ "  \"display\": {\n"
			+ "    \"icon\": {\n"
			+ "      \"id\": \"minecraft:paper\"\n"
			+ "    },\n"
			+ "    \"title\": " + messageJson + ",\n"
			+ "    \"description\": {\n"
			+ "      \"text\": \"\"\n"
			+ "    },\n"
			+ "    \"background\": \"minecraft:textures/gui/advancements/backgrounds/adventure.png\",\n"
			+ "    \"frame\": \"goal\",\n"
			+ "    \"announce_to_chat\": false,\n"
			+ "    \"show_toast\": true,\n"
			+ "    \"hidden\": true\n"
			+ "  },\n"
			+ "  \"requirements\": [\n"
			+ "    [\n"
			+ "      \"trigger\"\n"
			+ "    ]\n"
			+ "  ]\n"
			+ "}";

		Bukkit.getUnsafe().loadAdvancement(key, advancementJson);
	}

	@SuppressWarnings("deprecation")
	private void removeAdvancement(NamespacedKey key) {
		try {
			Bukkit.getUnsafe().removeAdvancement(key);
		} catch (Throwable ignored) {
		}
	}

}
