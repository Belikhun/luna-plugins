package dev.belikhun.luna.shop.mc12.bootstrap;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.auth.AuthMessages;
import dev.belikhun.luna.legacy.permission.PermissionService;
import dev.belikhun.luna.legacy.shop.ShopCategory;
import dev.belikhun.luna.legacy.shop.ShopItem;
import dev.belikhun.luna.legacy.shop.ShopItemStore;
import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.shop.ShopService;
import dev.belikhun.luna.legacy.shop.ShopTransactionPlayer;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.shop.mc12.gui.ShopAdminScreens;
import dev.belikhun.luna.shop.mc12.gui.ShopHistoryScreen;

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
	/** The node every other backend gates the shop's admin verbs on. */
	private static final String PERMISSION_ADMIN = "lunashop.admin";

	/** Server op, which counts as holding the node - see mayAdminister. */
	private static final int FALLBACK_PERMISSION_LEVEL = 2;

	private final ShopItemStore<ItemStack> store;
	private final ShopItems<ItemStack> items;
	private final PermissionService permissions;
	private final ShopAdminScreens screens;
	private final ShopService<EntityPlayerMP, ItemStack> service;
	private final ShopHistoryScreen history;

	public ShopAdminCommand(
		ShopItemStore<ItemStack> store,
		ShopItems<ItemStack> items,
		PermissionService permissions,
		ShopAdminScreens screens,
		ShopService<EntityPlayerMP, ItemStack> service,
		ShopHistoryScreen history
	) {
		this.store = store;
		this.items = items;
		this.permissions = permissions;
		this.screens = screens;
		this.service = service;
		this.history = history;
	}

	@Override
	public String getName() {
		return "shopadmin";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/shopadmin [gui|add|remove|list|verify|reload|history <người chơi> [trang]] …";
	}

	/**
	 * Zero, because the real check is the permission node below.
	 *
	 * `CommandBase` tests this *before* running, and 1.12.2 has no notion of a
	 * permission plugin; leaving it at 2 would gate the command on backend op and
	 * ignore the `lunashop.admin` grant that governs it on every other backend.
	 */
	@Override
	public int getRequiredPermissionLevel() {
		return 0;
	}

	@Override
	public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
		return true;
	}

	/**
	 * Whether this sender may administer the shop: the node, or server op.
	 *
	 * Op counts on purpose. On paper an op satisfies a permission check by default,
	 * so gating this on the node alone would make a 1.12.2 backend the one place an
	 * operator is refused a command they can run everywhere else - and with the
	 * mirror still warming up on join, it would refuse them intermittently, which is
	 * worse than refusing them consistently.
	 */
	private boolean mayAdminister(ICommandSender sender) {
		if (!(sender instanceof EntityPlayerMP)) {
			return true;
		}

		if (sender.canUseCommand(FALLBACK_PERMISSION_LEVEL, getName())) {
			return true;
		}

		EntityPlayerMP player = (EntityPlayerMP) sender;

		return permissions != null
			&& permissions.isAvailable()
			&& permissions.hasPermission(player.getUniqueID(), PERMISSION_ADMIN);
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos pos) {
		if (args.length == 1) {
			return getListOfStringsMatchingLastWord(args, "gui", "add", "remove", "list", "verify", "reload", "history");
		}

		// the names come from the history table, not the player list: the point of
		// this verb is reading someone who is not online
		if (args.length == 2 && "history".equalsIgnoreCase(args[0])) {
			return getListOfStringsMatchingLastWord(args, service.suggestHistoricalPlayers(args[1], 20));
		}

		if (args.length == 2 && "add".equalsIgnoreCase(args[0])) {
			return getListOfStringsMatchingLastWord(args, new ArrayList<String>(store.categories()));
		}

		return new ArrayList<String>();
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
		if (!mayAdminister(sender)) {
			reply(sender, "<red>❌ Bạn thiếu quyền <white>" + PERMISSION_ADMIN + "</white>.</red>");

			return;
		}

		String action = args.length == 0 ? "gui" : args[0].toLowerCase(java.util.Locale.ROOT);

		// bare `/shopadmin` opens the management screens, which is what an operator
		// wants nine times out of ten; the subcommands stay for the console and for
		// the one thing a GUI cannot do, which is prove the item codec round-trips
		if ("gui".equals(action)) {
			openGui(sender);

			return;
		}

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

		if ("history".equals(action)) {
			history(player, args);

			return;
		}

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

	private void openGui(ICommandSender sender) {
		if (!(sender instanceof EntityPlayerMP)) {
			reply(sender, AuthMessages.notAPlayer());

			return;
		}

		if (screens == null) {
			reply(sender, "<red>❌ Giao diện quản lý không khả dụng trên máy chủ này.</red>");

			return;
		}

		screens.openManagementMenu((EntityPlayerMP) sender);
	}

	/**
	 * Encode the held item and decode it straight back.
	 *
	 * The only honest test of the codec: the registries it needs exist solely on a
	 * running server, so this cannot be a unit test.
	 */
	/**
	 * `/shopadmin history <người chơi> [trang]`.
	 *
	 * The name is resolved against the history table rather than the player list,
	 * because the reason to run this is almost always that the player is not here to
	 * be asked. A name nobody has traded under is reported as such rather than
	 * opening an empty screen, which would read as "they bought nothing" instead of
	 * "no such player".
	 */
	private void history(EntityPlayerMP player, String[] args) {
		if (!service.isTransactionHistoryEnabled()) {
			reply(player, "<red>❌ Lịch sử giao dịch cần một database. Hãy bật khối <white>database</white> trong config của LunaCore.</red>");

			return;
		}

		if (args.length < 2 || Strings.isBlank(args[1])) {
			reply(player, "<red>❌ Cú pháp: <white>/shopadmin history <người chơi> [trang]</white></red>");

			return;
		}

		ShopTransactionPlayer target = service.findHistoricalPlayer(args[1]).orElse(null);

		if (target == null) {
			reply(player, "<red>❌ Không tìm thấy lịch sử cho người chơi: <white>" + args[1] + "</white>.</red>");

			return;
		}

		history.open(player, target.uuid(), target.name(), parsePage(args));
	}

	/** Pages are one-based for a human and zero-based for the screen. */
	private int parsePage(String[] args) {
		if (args.length < 3) {
			return 0;
		}

		try {
			return Math.max(0, Integer.parseInt(args[2].trim()) - 1);
		} catch (NumberFormatException notANumber) {
			return 0;
		}
	}

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
