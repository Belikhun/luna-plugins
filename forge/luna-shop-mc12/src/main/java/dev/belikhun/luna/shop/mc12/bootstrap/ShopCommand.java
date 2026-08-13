package dev.belikhun.luna.shop.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.shop.mc12.gui.ShopScreens;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * `/shop` on 1.12.2: open the shop, or search it.
 *
 * Search is a subcommand here rather than the button the modern builds draw,
 * because that button opens a chat prompt and the core has no prompt service on
 * this line. Typing the query is the same query either way.
 *
 * The admin verbs (`/shopadmin`) are not registered yet - the screens they open
 * are the half of the GUI still to be ported, and a command that opens nothing
 * would be worse than its absence.
 */
public final class ShopCommand extends CommandBase {
	private final ShopScreens screens;
	private final ShopItemStore<ItemStack> store;

	public ShopCommand(ShopScreens screens, ShopItemStore<ItemStack> store) {
		this.screens = screens;
		this.store = store;
	}

	@Override
	public String getName() {
		return "shop";
	}

	@Override
	public List<String> getAliases() {
		List<String> aliases = new ArrayList<String>();

		aliases.add("cuahang");

		return aliases;
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/shop [search <từ khoá>]";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
		return true;
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, "search");
		}

		return new ArrayList<String>();
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
		if (!(sender instanceof EntityPlayerMP)) {
			sender.sendMessage(LunaTextComponents.mini(AuthMessages.notAPlayer()));

			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) sender;

		if (args.length == 0) {
			screens.openMainMenu(player, 0);

			return;
		}

		if (!"search".equalsIgnoreCase(args[0])) {
			player.sendMessage(LunaTextComponents.mini("<red>❌ Cú pháp: <white>" + getUsage(sender) + "</white></red>"));

			return;
		}

		String query = join(args, 1);

		if (Strings.isBlank(query)) {
			player.sendMessage(LunaTextComponents.mini("<red>❌ Hãy nhập từ khoá cần tìm.</red>"));

			return;
		}

		if (store.search(query).isEmpty()) {
			player.sendMessage(LunaTextComponents.mini("<yellow>ℹ Không tìm thấy mặt hàng nào khớp <white>" + query + "</white>.</yellow>"));

			return;
		}

		screens.openSearchMenu(player, query, 0);
	}

	private String join(String[] args, int from) {
		StringBuilder out = new StringBuilder();

		for (int index = from; index < args.length; index += 1) {
			if (out.length() > 0) {
				out.append(' ');
			}

			out.append(args[index]);
		}

		return out.toString();
	}
}
