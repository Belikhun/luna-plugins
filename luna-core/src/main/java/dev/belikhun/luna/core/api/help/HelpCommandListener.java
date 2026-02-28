package dev.belikhun.luna.core.api.help;

import dev.belikhun.luna.core.LunaCoreServices;
import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class HelpCommandListener implements Listener {
	private final LunaCoreServices services;
	private final GuiManager guiManager;
	private final MiniMessage miniMessage;

	public HelpCommandListener(LunaCoreServices services) {
		this.services = services;
		this.guiManager = new GuiManager();
		this.miniMessage = MiniMessage.miniMessage();
		services.plugin().getServer().getPluginManager().registerEvents(guiManager, services.plugin());
		registerDefaultEntries();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerHelp(PlayerCommandPreprocessEvent event) {
		String message = event.getMessage().trim();
		if (!message.equalsIgnoreCase("/help") && !message.toLowerCase().startsWith("/help ")) {
			return;
		}

		event.setCancelled(true);
		openHelpGui(event.getPlayer());
	}

	public void openHelpGui(Player player) {
		String titleRaw = services.configStore().get("help.gui.title").asString("<gold>Hướng Dẫn Lệnh");
		int size = normalizeSize(services.configStore().get("help.gui.size").asInt(54));
		GuiView gui = new GuiView(size, miniMessage.deserialize(titleRaw));
		guiManager.track(gui);

		List<HelpEntry> entries = services.helpRegistry().visibleEntries(player);
		int maxItems = Math.min(entries.size(), Math.max(0, size - 9));
		for (int index = 0; index < maxItems; index++) {
			HelpEntry entry = entries.get(index);
			gui.setItem(index, commandItem(entry));
		}

		player.openInventory(gui.getInventory());
	}

	private int normalizeSize(int size) {
		int normalized = Math.max(9, size);
		int mod = normalized % 9;
		if (mod != 0) {
			normalized += 9 - mod;
		}
		return Math.min(normalized, 54);
	}

	private ItemStack commandItem(HelpEntry entry) {
		ItemStack item = new ItemStack(Material.BOOK);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(miniMessage.deserialize("<yellow>" + entry.command()));
		List<Component> lore = new ArrayList<>();
		lore.add(miniMessage.deserialize("<gray>Plugin: <white>" + entry.plugin()));
		lore.add(Component.empty());
		lore.add(miniMessage.deserialize("<gray>Cách dùng: <white>" + entry.usage()));
		lore.add(miniMessage.deserialize("<gray>Mô tả: <white>" + entry.description()));
		meta.lore(lore);
		item.setItemMeta(meta);
		return item;
	}

	private void registerDefaultEntries() {
		services.helpRegistry().register(new HelpEntry(
			"LunaCore",
			"/help",
			"/help",
			"Mở giao diện hướng dẫn các lệnh có sẵn.",
			null
		));
	}
}
