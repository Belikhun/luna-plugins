package dev.belikhun.luna.shop.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.shop.ShopCategory;
import dev.belikhun.luna.legacy.shop.ShopItem;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * `/shopadmin`: stock the shop from the item in your hand.
 *
 * The modern builds do all of this through the management GUI, which is the half
 * of the shop not yet ported. This is not a replacement for it - there is no
 * editor, no icon picker, no create-item flow - but it is what makes a 1.12.2
 * shop possible to fill at all, and it exercises the one thing that cannot be
 * tested off a running server: whether an item survives the trip through
 * `items.yml` and back.
 *
 * `verify` exists for exactly that. It encodes the held item, decodes it again
 * and reports what came back, so a codec fault shows up as a command saying so
 * rather than as a shop full of barriers a week later.
 */
public final class ShopAdminCommand extends CommandBase {
	/** Vanilla op level 2, the same bar the shop's other admin verbs sit behind. */
	private static final int PERMISSION_LEVEL = 2;

	private final ShopItemStore<ItemStack> store;
	private final ShopItems<ItemStack> items;

	public ShopAdminCommand(ShopItemStore<ItemStack> store, ShopItems<ItemStack> items) {
		this.store = store;
		this.items = items;
	}

	@Override
	public String getName() {
		return "shopadmin";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/shopadmin <add|remove|list|verify|reload> …";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return PERMISSION_LEVEL;
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "verify", "reload");
		}

		if (args.length == 2 && "add".equalsIgnoreCase(args[0])) {
			return getListOfStringsMatchingLastWord(args, new ArrayList<String>(store.categories()));
		}

		return new ArrayList<String>();
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
		if (args.length == 0) {
			reply(sender, "<yellow>ℹ Cú pháp: <white>" + getUsage(sender) + "</white></yellow>");

			return;
		}

		String action = args[0].toLowerCase(java.util.Locale.ROOT);

		if ("reload".equals(action)) {
			store.load();
			reply(sender, "<green>✔ Đã nạp lại items.yml: <white>" + store.all().size() + "</white> mặt hàng.</green>");

			return;
		}

		if ("list".equals(action)) {
			list(sender);

			return;
		}

		if (!(sender instanceof EntityPlayerMP)) {
			reply(sender, AuthMessages.notAPlayer());

			return;
		}

		EntityPlayerMP player = (EntityPlayerMP) sender;

		if ("verify".equals(action)) {
			verify(player);

			return;
		}

		if ("remove".equals(action)) {
			remove(sender, args);

			return;
		}

		if ("add".equals(action)) {
			add(player, args);

			return;
		}

		reply(sender, "<red>❌ Không rõ lệnh con <white>" + action + "</white>.</red>");
	}

	/**
	 * Encode the held item and decode it straight back.
	 *
	 * The only honest test of the codec: the registries it needs exist solely on a
	 * running server, so this cannot be a unit test.
	 */
	private void verify(EntityPlayerMP player) {
		ItemStack held = player.getHeldItemMainhand();

		if (items.isEmpty(held)) {
			reply(player, "<red>❌ Hãy cầm một vật phẩm trên tay.</red>");

			return;
		}

		String encoded = items.encode(held);

		if (Strings.isBlank(encoded)) {
			reply(player, "<red>❌ Không mã hoá được vật phẩm này.</red>");

			return;
		}

		ItemStack decoded = items.decode(encoded);
		boolean same = items.sameItemAndData(held, decoded);

		reply(player, "<gray>Đã mã hoá: <white>" + encoded.length() + "</white> ký tự");
		reply(player, "<gray>Giải mã ra: <white>" + items.itemId(decoded) + "</white> <gray>(" + items.displayName(decoded) + "<gray>)");
		reply(player, same
			? "<green>✔ Mã hoá và giải mã khớp nhau.</green>"
			: "<red>❌ Vật phẩm giải mã KHÔNG khớp với vật phẩm gốc.</red>");
	}

	private void add(EntityPlayerMP player, String[] args) {
		if (args.length < 4) {
			reply(player, "<yellow>ℹ Cú pháp: <white>/shopadmin add <danh_mục> <giá_mua> <giá_bán></white></yellow>");

			return;
		}

		ItemStack held = player.getHeldItemMainhand();

		if (items.isEmpty(held)) {
			reply(player, "<red>❌ Hãy cầm vật phẩm muốn bán trên tay.</red>");

			return;
		}

		double buyPrice;
		double sellPrice;

		try {
			buyPrice = Double.parseDouble(args[2]);
			sellPrice = Double.parseDouble(args[3]);
		} catch (NumberFormatException ignored) {
			reply(player, "<red>❌ Giá phải là số.</red>");

			return;
		}

		String category = args[1].trim().toLowerCase(java.util.Locale.ROOT);

		if (!store.findCategory(category).isPresent()) {
			store.upsertCategory(ShopCategory.defaultCategory(category));
			store.upsertCategoryIcon(category, held);
		}

		ShopItem created = ShopItem.fromItemStackAutoId(items, category, buyPrice, sellPrice, 0, 0, held);

		store.upsert(created);
		store.save();

		reply(player, "<green>✔ Đã thêm <white>" + items.displayName(held) + "</white> vào danh mục <white>"
			+ category + "</white> (id <white>" + created.id() + "</white>).</green>");
	}

	private void remove(ICommandSender sender, String[] args) {
		if (args.length < 2) {
			reply(sender, "<yellow>ℹ Cú pháp: <white>/shopadmin remove <id></white></yellow>");

			return;
		}

		if (store.remove(args[1])) {
			store.save();
			reply(sender, "<green>✔ Đã xoá mặt hàng <white>" + args[1] + "</white>.</green>");

			return;
		}

		reply(sender, "<red>❌ Không tìm thấy mặt hàng <white>" + args[1] + "</white>.</red>");
	}

	private void list(ICommandSender sender) {
		List<ShopItem> all = store.all();

		reply(sender, "<aqua>ℹ Shop có <white>" + all.size() + "</white> mặt hàng trong <white>"
			+ store.categories().size() + "</white> danh mục.</aqua>");

		int shown = 0;

		for (ShopItem item : all) {
			if (shown >= 10) {
				reply(sender, "<dark_gray>… và " + (all.size() - shown) + " mặt hàng nữa.</dark_gray>");

				break;
			}

			reply(sender, "<gray>• <white>" + item.id() + "</white> <gray>(" + item.category()
				+ ") mua <gold>" + item.buyPrice() + "</gold> bán <gold>" + item.sellPrice() + "</gold></gray>");
			shown += 1;
		}
	}

	private void reply(ICommandSender sender, String message) {
		sender.sendMessage(LunaTextComponents.mini(message));
	}
}
