package dev.belikhun.luna.hat;

import dev.belikhun.luna.core.api.ui.LunaUi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class HatHandler implements BasicCommand, Listener {
	public HatHandler() {
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (sender instanceof Player player) {
			PlayerInventory inv = player.getInventory();
			ItemStack held = inv.getItemInMainHand();

			if (checkValidHat(player, held)) {
				if (held.getType() == Material.AIR) {
					ItemStack helmet = inv.getHelmet();
					inv.setHelmet(null);
					inv.setItemInMainHand(helmet == null ? new ItemStack(Material.AIR) : helmet);
				} else {
					equipFromMainHand(player, held);
				}
				player.updateInventory();
				player.sendMessage(LunaUi.mini("<green>✔ Đã đội vật phẩm lên mũ.</green>"));
			}
		} else {
			sender.sendMessage(LunaUi.mini("<red>❌ Chỉ người chơi mới dùng lệnh này.</red>"));
		}
	}

	@Override
	public String permission() {
		return null;
	}

	@EventHandler
	public void onClickInHelmetSlot(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		if (isNumberKeyPlaceIntoHelmetSlot(event)) {
			handleNumberKeyEquip(player, event);
			return;
		}

		if (!isCursorPlaceIntoHelmetSlot(event)) {
			return;
		}

		ItemStack cursorItem = event.getCursor();
		if (cursorItem == null || cursorItem.getType() == Material.AIR) {
			return;
		}

		if (cursorItem.getType().getEquipmentSlot() == EquipmentSlot.HEAD) {
			return;
		}

		if (!checkValidHat(player, cursorItem)) {
			return;
		}

		event.setCancelled(true);
		PlayerInventory inventory = player.getInventory();
		ItemStack oldHelmet = inventory.getHelmet();
		ItemStack hatOne = cursorItem.clone();
		hatOne.setAmount(1);

		ItemStack cursorRemaining = new ItemStack(Material.AIR);
		if (cursorItem.getAmount() > 1) {
			cursorRemaining = cursorItem.clone();
			cursorRemaining.setAmount(cursorItem.getAmount() - 1);
		}
		final ItemStack finalCursorRemaining = cursorRemaining;

		inventory.setHelmet(hatOne);

		if (oldHelmet == null || oldHelmet.getType() == Material.AIR) {
			player.setItemOnCursor(finalCursorRemaining);
		} else {
			player.setItemOnCursor(oldHelmet);
			giveOrDrop(player, finalCursorRemaining);
		}

		player.updateInventory();
		player.sendMessage(LunaUi.mini("<green>✔ Đã đội vật phẩm lên mũ.</green>"));
	}

	private boolean isCursorPlaceIntoHelmetSlot(InventoryClickEvent event) {
		ClickType click = event.getClick();
		if (!(click == ClickType.LEFT || click == ClickType.RIGHT)) {
			return false;
		}

		return isHelmetSlotClick(event);
	}

	private boolean isNumberKeyPlaceIntoHelmetSlot(InventoryClickEvent event) {
		return event.getClick() == ClickType.NUMBER_KEY && isHelmetSlotClick(event);
	}

	private boolean isHelmetSlotClick(InventoryClickEvent event) {
		if (event.getSlotType() != InventoryType.SlotType.ARMOR) {
			return false;
		}

		boolean legacyHelmetRawSlot = event.getView().getTopInventory().getType() == InventoryType.CRAFTING
			&& event.getRawSlot() == 5;
		boolean playerHelmetSlot = event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 39;

		return playerHelmetSlot || legacyHelmetRawSlot;
	}

	private void handleNumberKeyEquip(Player player, InventoryClickEvent event) {
		int hotbarButton = event.getHotbarButton();
		if (hotbarButton < 0 || hotbarButton > 8) {
			return;
		}

		PlayerInventory inventory = player.getInventory();
		ItemStack hotbarItem = inventory.getItem(hotbarButton);
		if (hotbarItem == null || hotbarItem.getType() == Material.AIR) {
			return;
		}

		if (hotbarItem.getType().getEquipmentSlot() == EquipmentSlot.HEAD) {
			return;
		}

		if (!checkValidHat(player, hotbarItem)) {
			return;
		}

		event.setCancelled(true);

		ItemStack oldHelmet = inventory.getHelmet();
		ItemStack hatOne = hotbarItem.clone();
		hatOne.setAmount(1);

		if (hotbarItem.getAmount() > 1) {
			ItemStack remaining = hotbarItem.clone();
			remaining.setAmount(hotbarItem.getAmount() - 1);
			inventory.setItem(hotbarButton, remaining);
		} else {
			inventory.setItem(hotbarButton, new ItemStack(Material.AIR));
		}

		inventory.setHelmet(hatOne);
		giveOrDrop(player, oldHelmet);
		player.updateInventory();
		player.sendMessage(LunaUi.mini("<green>✔ Đã đội vật phẩm lên mũ.</green>"));
	}

	private void equipFromMainHand(Player player, ItemStack held) {
		PlayerInventory inventory = player.getInventory();
		ItemStack oldHelmet = inventory.getHelmet();

		ItemStack hatOne = held.clone();
		hatOne.setAmount(1);

		if (held.getAmount() > 1) {
			ItemStack remaining = held.clone();
			remaining.setAmount(held.getAmount() - 1);
			inventory.setItemInMainHand(remaining);
		} else {
			inventory.setItemInMainHand(new ItemStack(Material.AIR));
		}

		inventory.setHelmet(hatOne);
		giveOrDrop(player, oldHelmet);
	}

	private void giveOrDrop(Player player, ItemStack item) {
		if (item == null || item.getType() == Material.AIR) {
			return;
		}

		player.getInventory().addItem(item)
			.values()
			.forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
	}

	private boolean checkValidHat(Player player, ItemStack held) {
		if (held.getType() == Material.AIR) {
			return true;
		}

		boolean hasCategoryPerm = held.getType().isBlock()
			? player.hasPermission("hat.blocks")
			: player.hasPermission("hat.items");

		if (hasCategoryPerm) {
			return true;
		}

		player.sendMessage(LunaUi.mini("<red>❌ Bạn không có quyền đội vật phẩm này.</red>"));
		return false;
	}

}

