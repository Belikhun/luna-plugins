package dev.belikhun.luna.core.api.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class LunaUi {
	private static final MiniMessage MINI = MiniMessage.miniMessage();

	private LunaUi() {
	}

	public static Component mini(String miniMessage) {
		return MINI.deserialize("<!italic>" + miniMessage);
	}

	public static Component guiTitle(String text) {
		return mini(LunaGuiTitle.plain(text));
	}

	public static Component guiTitleBreadcrumb(String... segments) {
		return mini(LunaGuiTitle.breadcrumb(segments));
	}

	public static String muted(String text) {
		return "<color:" + LunaPalette.NEUTRAL_500 + ">" + text + "</color>";
	}

	public static String success(String text) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">" + text + "</color>";
	}

	public static String warning(String text) {
		return "<color:" + LunaPalette.WARNING_500 + ">" + text + "</color>";
	}

	public static String danger(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}

	public static String info(String text) {
		return "<color:" + LunaPalette.INFO_500 + ">" + text + "</color>";
	}

	public static ItemStack item(Material material, String title, List<Component> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(mini(title));
		meta.lore(lore);
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		item.setItemMeta(meta);
		return item;
	}
}
