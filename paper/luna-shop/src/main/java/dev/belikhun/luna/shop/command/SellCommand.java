package dev.belikhun.luna.shop.command;

import dev.belikhun.luna.shop.gui.ShopGuiController;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** /sell opens the bulk sell tray; the same tray the shop footer opens. */
public final class SellCommand implements BasicCommand {
	private final ShopGuiController guiController;

	public SellCommand(ShopGuiController guiController) {
		this.guiController = guiController;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();

		if (!(sender instanceof Player player)) {
			sender.sendRichMessage("<red>❌ Lệnh này chỉ có thể dùng trong game.</red>");
			return;
		}

		guiController.openSellInventory(player);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		return List.of();
	}
}
