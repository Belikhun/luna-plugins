package dev.belikhun.luna.shop.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.shop.api.ShopItemIds;
import dev.belikhun.luna.shop.api.ShopTransactionPlayer;
import dev.belikhun.luna.shop.mc.gui.ShopGuiController;
import dev.belikhun.luna.shop.mc.model.ShopCategory;
import dev.belikhun.luna.shop.mc.model.ShopItem;
import dev.belikhun.luna.shop.mc.service.ShopService;
import dev.belikhun.luna.shop.mc.store.ShopItemStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /shop}, {@code /shopadmin} and {@code /lunashop} as brigadier trees.
 *
 * Paper builds the same commands from a flat {@code String[]}, which is why its
 * usage text has to be printed by hand; here brigadier rejects a wrong shape
 * before the handler runs, so the printed usage only survives where it tells the
 * operator something brigadier cannot - what a limit of "none" means, mostly.
 *
 * Every executor resolves the runtime through a supplier. The command tree is
 * built while the server is still loading, before the shop exists.
 */
public final class ShopCommands {
	private static final String PERMISSION_ADMIN = "lunashop.admin";
	private static final String PLAYERS_ONLY = "<red>❌ Lệnh này chỉ có thể dùng trong game.</red>";
	private static final String NOT_READY = "<red>❌ LunaShop chưa sẵn sàng.</red>";
	private static final String ADMIN_HEADER = "<gold>♦ Lệnh quản trị Luna Shop</gold>";

	private final Supplier<ShopRuntime> runtime;

	public ShopCommands(Supplier<ShopRuntime> runtime) {
		this.runtime = runtime;
	}

	/** Everything a command needs, resolved together so a half-started shop cannot be reached. */
	public record ShopRuntime(ShopService service, ShopItemStore store, ShopGuiController gui, PermissionService permissions) {
	}

	public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		registerShop(dispatcher, "shop");
		registerShop(dispatcher, "buy");
		registerShop(dispatcher, "store");
		registerShop(dispatcher, "b");
		registerShopAdmin(dispatcher);
		registerLunaShop(dispatcher);
	}

	// ---------------------------------------------------------------- /shop

	private void registerShop(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
		dispatcher.register(Commands.literal(root)
			.executes(context -> withPlayer(context, (player, shop) -> {
				shop.gui().openMainMenu(player, 0);
				return 1;
			}))
			.then(Commands.literal("history")
				.executes(context -> withPlayer(context, (player, shop) -> {
					shop.gui().openTransactionHistory(player, 0);
					return 1;
				})))
			.then(Commands.literal("search")
				.then(Commands.argument("từ_khóa", StringArgumentType.greedyString())
					.suggests(itemSuggestions())
					.executes(context -> withPlayer(context, (player, shop) -> {
						shop.gui().openSearchMenu(player, StringArgumentType.getString(context, "từ_khóa"), 0);
						return 1;
					}))))
			.then(Commands.literal("category")
				.then(Commands.argument("tên_danh_mục", StringArgumentType.word())
					.suggests(categorySuggestions())
					.executes(context -> withPlayer(context, (player, shop) -> {
						shop.gui().openCategoryMenu(player, StringArgumentType.getString(context, "tên_danh_mục"), 0);
						return 1;
					})))));
	}

	// ---------------------------------------------------------------- /shopadmin

	private void registerShopAdmin(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("shopadmin")
			.requires(this::mayAdminister)
			.executes(context -> withShop(context, (source, shop) -> {
				sendHelp(source);
				return 1;
			}))
			.then(Commands.literal("open")
				.executes(context -> withPlayer(context, (player, shop) -> {
					shop.gui().openManagementMenu(player);
					return 1;
				})))
			.then(Commands.literal("reload")
				.executes(context -> withShop(context, (source, shop) -> {
					shop.store().load();
					tell(source, "<green>✔ Đã reload dữ liệu shop từ items.yml.</green>");
					return 1;
				})))
			.then(Commands.literal("list")
				.executes(context -> withShop(context, (source, shop) -> {
					listItems(source, shop);
					return 1;
				})))
			.then(Commands.literal("remove")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(itemIdSuggestions())
					.executes(context -> withShop(context, (source, shop) -> {
						if (shop.store().remove(StringArgumentType.getString(context, "id"))) {
							tell(source, "<green>✔ Đã xóa item khỏi shop.</green>");
							return 1;
						}

						tell(source, "<red>❌ Không tìm thấy item theo id.</red>");
						return 0;
					}))))
			.then(addBranch())
			.then(limitBranch())
			.then(categoryBranch())
			.then(historyBranch()));
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> addBranch() {
		return Commands.literal("add")
			.then(Commands.argument("category", StringArgumentType.word())
				.suggests(categorySuggestions())
				.then(Commands.argument("buyPrice", DoubleArgumentType.doubleArg(0D))
					.then(Commands.argument("sellPrice", DoubleArgumentType.doubleArg(0D))
						.executes(context -> addItem(context, 0, 0))
						.then(Commands.argument("buyLimit", StringArgumentType.word())
							.then(Commands.argument("sellLimit", StringArgumentType.word())
								.executes(context -> addItemWithLimits(context)))))));
	}

	private int addItemWithLimits(CommandContext<CommandSourceStack> context) {
		int buyLimit;
		int sellLimit;

		try {
			buyLimit = ShopGuiController.parseTradeLimitInput(StringArgumentType.getString(context, "buyLimit"));
			sellLimit = ShopGuiController.parseTradeLimitInput(StringArgumentType.getString(context, "sellLimit"));
		} catch (IllegalArgumentException exception) {
			tell(context.getSource(), "<red>❌ Hạn mức phải là số nguyên >= 0 hoặc <white>none</white>.</red>");
			return 0;
		}

		return addItem(context, buyLimit, sellLimit);
	}

	private int addItem(CommandContext<CommandSourceStack> context, int buyLimit, int sellLimit) {
		return withPlayer(context, (player, shop) -> {
			ItemStack hand = player.getMainHandItem();

			if (hand.isEmpty()) {
				tell(context.getSource(), "<red>❌ Bạn cần cầm vật phẩm trên tay để thêm vào shop.</red>");
				return 0;
			}

			String category = StringArgumentType.getString(context, "category");

			if (shop.store().findCategory(category).isEmpty()) {
				tell(context.getSource(), "<red>❌ Danh mục chưa tồn tại. Dùng "
					+ CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("create"), CommandStrings.required("id", "text"))
					+ " <red>trước.</red>");
				return 0;
			}

			Optional<ShopItem> duplicate = shop.store().findBySimilarItem(hand);

			if (duplicate.isPresent()) {
				tell(context.getSource(), "<red>❌ Item này đã có trong shop với id <white>" + duplicate.get().id() + "</white>.</red>");
				return 0;
			}

			ShopItem created = ShopItem.fromItemStackAutoId(
				context.getSource().getServer(),
				category,
				DoubleArgumentType.getDouble(context, "buyPrice"),
				DoubleArgumentType.getDouble(context, "sellPrice"),
				buyLimit,
				sellLimit,
				hand
			);

			shop.store().upsert(created);
			tell(context.getSource(), "<green>✔ Đã lưu item <white>" + created.id() + "</white> vào danh mục <white>" + created.category() + "</white>.</green>");
			return 1;
		});
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> limitBranch() {
		return Commands.literal("limit")
			.then(Commands.literal("set")
				.then(Commands.argument("itemId", StringArgumentType.word())
					.suggests(itemIdSuggestions())
					.then(Commands.argument("buyLimit", StringArgumentType.word())
						.then(Commands.argument("sellLimit", StringArgumentType.word())
							.executes(context -> withShop(context, (source, shop) -> setLimits(context, source, shop)))))))
			.then(Commands.literal("remove")
				.then(Commands.argument("itemId", StringArgumentType.word())
					.suggests(itemIdSuggestions())
					.executes(context -> withShop(context, (source, shop) -> {
						ShopItem item = shop.store().find(StringArgumentType.getString(context, "itemId")).orElse(null);

						if (item == null) {
							tell(source, "<red>❌ Không tìm thấy item theo id.</red>");
							return 0;
						}

						shop.store().upsert(item.withBuyTradeLimit(0).withSellTradeLimit(0));
						tell(source, "<green>✔ Đã gỡ toàn bộ hạn mức của item <white>" + item.id() + "</white>.</green>");
						return 1;
					}))))
			.then(Commands.literal("show")
				.then(Commands.argument("itemId", StringArgumentType.word())
					.suggests(itemIdSuggestions())
					.executes(context -> withShop(context, (source, shop) -> {
						ShopItem item = shop.store().find(StringArgumentType.getString(context, "itemId")).orElse(null);

						if (item == null) {
							tell(source, "<red>❌ Không tìm thấy item theo id.</red>");
							return 0;
						}

						tell(source, "<aqua>ℹ Hạn mức của <white>" + item.id() + "</white>: mua/ngày <yellow>"
							+ displayLimit(item.buyTradeLimit()) + "</yellow>, bán/ngày <yellow>"
							+ displayLimit(item.sellTradeLimit()) + "</yellow>.</aqua>");
						return 1;
					}))));
	}

	private int setLimits(CommandContext<CommandSourceStack> context, CommandSourceStack source, ShopRuntime shop) {
		ShopItem item = shop.store().find(StringArgumentType.getString(context, "itemId")).orElse(null);

		if (item == null) {
			tell(source, "<red>❌ Không tìm thấy item theo id.</red>");
			return 0;
		}

		int buyLimit;
		int sellLimit;

		try {
			buyLimit = ShopGuiController.parseTradeLimitInput(StringArgumentType.getString(context, "buyLimit"));
			sellLimit = ShopGuiController.parseTradeLimitInput(StringArgumentType.getString(context, "sellLimit"));
		} catch (IllegalArgumentException exception) {
			tell(source, "<red>❌ Hạn mức phải là số nguyên >= 0 hoặc <white>none</white>.</red>");
			return 0;
		}

		shop.store().upsert(item.withBuyTradeLimit(buyLimit).withSellTradeLimit(sellLimit));
		tell(source, "<green>✔ Đã cập nhật hạn mức cho <white>" + item.id() + "</white> | mua: <yellow>"
			+ displayLimit(buyLimit) + "</yellow>, bán: <yellow>" + displayLimit(sellLimit) + "</yellow>.</green>");
		return 1;
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> categoryBranch() {
		return Commands.literal("category")
			.then(Commands.literal("list")
				.executes(context -> withShop(context, (source, shop) -> {
					listCategories(source, shop);
					return 1;
				})))
			.then(Commands.literal("create")
				.then(Commands.argument("id", StringArgumentType.word())
					.executes(context -> withPlayer(context, (player, shop) -> {
						String id = StringArgumentType.getString(context, "id");

						if (shop.store().findCategory(id).isPresent()) {
							tell(context.getSource(), "<red>❌ Danh mục đã tồn tại.</red>");
							return 0;
						}

						ItemStack hand = player.getMainHandItem();

						if (hand.isEmpty()) {
							tell(context.getSource(), "<red>❌ Bạn cần cầm item đại diện category trên tay.</red>");
							return 0;
						}

						shop.store().upsertCategory(ShopCategory.fromIcon(context.getSource().getServer(), id, hand));
						tell(context.getSource(), "<green>✔ Đã tạo danh mục <white>" + ShopItemIds.normalizeCategory(id) + "</white>.</green>");
						return 1;
					}))))
			.then(Commands.literal("seticon")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(categorySuggestions())
					.executes(context -> withPlayer(context, (player, shop) -> {
						String id = StringArgumentType.getString(context, "id");

						if (shop.store().findCategory(id).isEmpty()) {
							tell(context.getSource(), "<red>❌ Danh mục không tồn tại.</red>");
							return 0;
						}

						ItemStack hand = player.getMainHandItem();

						if (hand.isEmpty()) {
							tell(context.getSource(), "<red>❌ Bạn cần cầm item đại diện category trên tay.</red>");
							return 0;
						}

						shop.store().upsertCategoryIcon(id, hand);
						tell(context.getSource(), "<green>✔ Đã cập nhật icon cho danh mục <white>" + ShopItemIds.normalizeCategory(id) + "</white>.</green>");
						return 1;
					}))))
			.then(Commands.literal("displayname")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(categorySuggestions())
					.then(Commands.argument("displayName", StringArgumentType.greedyString())
						.executes(context -> withShop(context, (source, shop) -> {
							String id = StringArgumentType.getString(context, "id");

							if (shop.store().findCategory(id).isEmpty()) {
								tell(source, "<red>❌ Danh mục không tồn tại.</red>");
								return 0;
							}

							String displayName = StringArgumentType.getString(context, "displayName").trim();

							if (displayName.isBlank()) {
								tell(source, "<red>❌ Tên hiển thị không được để trống.</red>");
								return 0;
							}

							shop.store().updateCategoryDisplayName(id, displayName);
							tell(source, "<green>✔ Đã cập nhật tên hiển thị cho danh mục <white>"
								+ ShopItemIds.normalizeCategory(id) + "</white>: " + displayName + "<green>.</green>");
							return 1;
						})))))
			.then(Commands.literal("rename")
				.then(Commands.argument("oldId", StringArgumentType.word())
					.suggests(categorySuggestions())
					.then(Commands.argument("newId", StringArgumentType.word())
						.executes(context -> withShop(context, (source, shop) -> {
							String oldId = StringArgumentType.getString(context, "oldId");
							String newId = StringArgumentType.getString(context, "newId");

							if (shop.store().findCategory(oldId).isEmpty()) {
								tell(source, "<red>❌ Danh mục cũ không tồn tại.</red>");
								return 0;
							}

							if (shop.store().findCategory(newId).isPresent()) {
								tell(source, "<red>❌ Danh mục mới đã tồn tại.</red>");
								return 0;
							}

							shop.store().renameCategory(oldId, newId);
							tell(source, "<green>✔ Đã đổi tên danh mục từ <white>" + ShopItemIds.normalizeCategory(oldId)
								+ "</white> sang <white>" + ShopItemIds.normalizeCategory(newId) + "</white>.</green>");
							return 1;
						})))))
			.then(Commands.literal("delete")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(categorySuggestions())
					.executes(context -> deleteCategory(context, null))
					.then(Commands.argument("moveTo", StringArgumentType.word())
						.suggests(categorySuggestions())
						.executes(context -> deleteCategory(context, StringArgumentType.getString(context, "moveTo"))))));
	}

	private int deleteCategory(CommandContext<CommandSourceStack> context, String moveTo) {
		return withShop(context, (source, shop) -> {
			String id = StringArgumentType.getString(context, "id");

			if (!shop.store().deleteCategory(id, moveTo)) {
				tell(source, "<red>❌ Không thể xóa danh mục. Nếu còn item, hãy chỉ định danh mục đích để chuyển: </red>"
					+ CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("delete"),
						CommandStrings.required("id", "text"), CommandStrings.required("moveTo", "text")));
				return 0;
			}

			tell(source, "<green>✔ Đã xóa danh mục <white>" + ShopItemIds.normalizeCategory(id) + "</white>.</green>");
			return 1;
		});
	}

	private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> historyBranch() {
		return Commands.literal("history")
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(historyPlayerSuggestions())
				.executes(context -> openAdminHistory(context, 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(context -> openAdminHistory(context, IntegerArgumentType.getInteger(context, "page")))));
	}

	private int openAdminHistory(CommandContext<CommandSourceStack> context, int page) {
		return withPlayer(context, (admin, shop) -> {
			String name = StringArgumentType.getString(context, "player");
			ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);

			if (target != null) {
				shop.gui().openTransactionHistoryAdmin(admin, target.getUUID(), target.getName().getString(), Math.max(0, page - 1));
				return 1;
			}

			ShopTransactionPlayer historical = shop.service().findHistoricalPlayer(name).orElse(null);

			if (historical == null) {
				tell(context.getSource(), "<red>❌ Không tìm thấy lịch sử cho người chơi: <white>" + name + "</white>.</red>");
				return 0;
			}

			shop.gui().openTransactionHistoryAdmin(admin, historical.uuid(), historical.name(), Math.max(0, page - 1));
			return 1;
		});
	}

	// ---------------------------------------------------------------- /lunashop

	private void registerLunaShop(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("lunashop")
			.requires(this::mayAdminister)
			.then(Commands.literal("reload")
				.executes(context -> withShop(context, (source, shop) -> {
					shop.store().load();
					tell(source, "<green>✔ Đã reload dữ liệu shop từ items.yml.</green>");
					return 1;
				}))));
	}

	// ---------------------------------------------------------------- helpers

	private void listItems(CommandSourceStack source, ShopRuntime shop) {
		List<ShopItem> items = shop.store().all();
		tell(source, "<gold>♦ Danh sách mặt hàng hiện có: <white>" + items.size() + "</white></gold>");

		for (ShopItem item : items) {
			tell(source, "<gray>● <white>" + item.id() + "</white> <dark_gray>(" + item.category() + ")</dark_gray>"
				+ " <green>Mua: " + shop.service().formatMoney(item.buyPrice()) + "</green>"
				+ " <yellow>Bán: " + shop.service().formatMoney(item.sellPrice()) + "</yellow>"
				+ " <gray>| HM mua: " + displayLimit(item.buyTradeLimit())
				+ ", HM bán: " + displayLimit(item.sellTradeLimit()) + "</gray>");
		}
	}

	private void listCategories(CommandSourceStack source, ShopRuntime shop) {
		List<ShopCategory> categories = shop.store().allCategories();
		tell(source, "<gold>♦ Danh mục hiện có: <white>" + categories.size() + "</white></gold>");

		for (ShopCategory category : categories) {
			String display = category.hasDisplayName() ? category.displayName() : "<gray>(mặc định)</gray>";
			tell(source, "<gray>● <white>" + category.id() + "</white> - " + display
				+ " <dark_gray>(" + shop.store().byCategory(category.id()).size() + " item)</dark_gray>");
		}
	}

	private void sendHelp(CommandSourceStack source) {
		tell(source, ADMIN_HEADER);
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("add"), CommandStrings.required("category", "text"), CommandStrings.required("buyPrice", "number"), CommandStrings.required("sellPrice", "number"), CommandStrings.optional("buyLimit", "number|none"), CommandStrings.optional("sellLimit", "number|none")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("create"), CommandStrings.required("id", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("seticon"), CommandStrings.required("id", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("displayname"), CommandStrings.required("id", "text"), CommandStrings.required("displayName", "mini_message...")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("list")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("rename"), CommandStrings.required("oldId", "text"), CommandStrings.required("newId", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("category"), CommandStrings.literal("delete"), CommandStrings.required("id", "text"), CommandStrings.optional("moveTo", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("limit"), CommandStrings.literal("set"), CommandStrings.required("itemId", "text"), CommandStrings.required("buyLimit", "number|none"), CommandStrings.required("sellLimit", "number|none")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("limit"), CommandStrings.literal("remove"), CommandStrings.required("itemId", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("limit"), CommandStrings.literal("show"), CommandStrings.required("itemId", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("remove"), CommandStrings.required("id", "text")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("list")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("history"), CommandStrings.required("player", "text"), CommandStrings.optional("page", "number")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("reload")));
		tell(source, CommandStrings.syntax("/shopadmin", CommandStrings.literal("open")));
	}

	private String displayLimit(int limit) {
		return limit <= 0 ? "Không giới hạn" : String.valueOf(limit);
	}

	/**
	 * The admin gate.
	 *
	 * LuckPerms is the only authority asked, for the same reason the vault
	 * backend's history command asks nothing else: the game's own op check is
	 * spelled differently on the two game lines this jar pair covers, and the
	 * fleet always runs LuckPerms. A non-player source - console, command block -
	 * is always allowed.
	 */
	private boolean mayAdminister(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return true;
		}

		ShopRuntime shop = runtime.get();

		return shop != null
			&& shop.permissions() != null
			&& shop.permissions().isAvailable()
			&& shop.permissions().hasPermission(player.getUUID(), PERMISSION_ADMIN);
	}

	private SuggestionProvider<CommandSourceStack> categorySuggestions() {
		return (context, builder) -> {
			ShopRuntime shop = runtime.get();
			return SharedSuggestionProvider.suggest(shop == null ? List.of() : shop.store().categories(), builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> itemIdSuggestions() {
		return (context, builder) -> {
			ShopRuntime shop = runtime.get();

			if (shop == null) {
				return SharedSuggestionProvider.suggest(List.of(), builder);
			}

			return SharedSuggestionProvider.suggest(shop.store().all().stream().map(ShopItem::id).toList(), builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> itemSuggestions() {
		return (context, builder) -> {
			ShopRuntime shop = runtime.get();

			if (shop == null) {
				return SharedSuggestionProvider.suggest(List.of(), builder);
			}

			List<String> values = new ArrayList<>();

			for (ShopItem item : shop.store().all()) {
				values.add(item.id());
				values.add(item.category());
			}

			return SharedSuggestionProvider.suggest(values, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> historyPlayerSuggestions() {
		return (context, builder) -> {
			ShopRuntime shop = runtime.get();

			if (shop == null) {
				return SharedSuggestionProvider.suggest(List.of(), builder);
			}

			return SharedSuggestionProvider.suggest(shop.service().suggestHistoricalPlayers(builder.getRemaining(), 20), builder);
		};
	}

	private int withShop(CommandContext<CommandSourceStack> context, ShopAction action) {
		ShopRuntime shop = runtime.get();

		if (shop == null) {
			tell(context.getSource(), NOT_READY);
			return 0;
		}

		return action.run(context.getSource(), shop);
	}

	private int withPlayer(CommandContext<CommandSourceStack> context, PlayerAction action) {
		if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
			tell(context.getSource(), PLAYERS_ONLY);
			return 0;
		}

		ShopRuntime shop = runtime.get();

		if (shop == null) {
			tell(context.getSource(), NOT_READY);
			return 0;
		}

		return action.run(player, shop);
	}

	private void tell(CommandSourceStack source, String miniMessage) {
		source.sendSystemMessage(LunaTextComponents.mini(miniMessage));
	}

	@FunctionalInterface
	private interface ShopAction {
		int run(CommandSourceStack source, ShopRuntime shop);
	}

	@FunctionalInterface
	private interface PlayerAction {
		int run(ServerPlayer player, ShopRuntime shop);
	}
}
