package dev.belikhun.luna.hat;

import dev.belikhun.luna.core.api.ui.LunaUi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
		if (event.getInventory().getType() == InventoryType.CRAFTING
			&& event.getRawSlot() == 5
			&& event.getWhoClicked().getItemOnCursor().getType() != Material.AIR
			&& event.getWhoClicked().getItemOnCursor().getType().getEquipmentSlot() != EquipmentSlot.HEAD) {

			Player player = (Player) event.getWhoClicked();
			ItemStack cursorItem = player.getItemOnCursor();
			ItemStack oldHelmet = player.getInventory().getHelmet();

			if (checkValidHat(player, cursorItem)) {
				event.setCancelled(true);

				ItemStack hatOne = cursorItem.clone();
				hatOne.setAmount(1);

				ItemStack cursorRemaining = null;
				if (cursorItem.getAmount() > 1) {
					cursorRemaining = cursorItem.clone();
					cursorRemaining.setAmount(cursorItem.getAmount() - 1);
				}

				player.setItemOnCursor(cursorRemaining == null ? new ItemStack(Material.AIR) : cursorRemaining);
				player.getInventory().setHelmet(hatOne);
				giveOrDrop(player, oldHelmet);
				player.updateInventory();
				player.sendMessage(LunaUi.mini("<green>✔ Đã đội vật phẩm lên mũ.</green>"));
			}
		}
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
		if (player.hasPermission("hat." + held.getType().name()) || held.getType() == Material.AIR) {
			return true;
		} else {
			player.sendMessage(LunaUi.mini("<red>❌ Bạn không có quyền đội vật phẩm này.</red>"));
		}
		return false;
	}
}
