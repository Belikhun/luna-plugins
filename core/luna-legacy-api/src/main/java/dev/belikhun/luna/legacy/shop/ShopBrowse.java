package dev.belikhun.luna.legacy.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Where a player is in the shop, and in what order they are seeing it.
 *
 * Browsing state rather than screen state: which category or search, which page,
 * which sort. A GUI turns this into slots; nothing here knows what a slot is, so
 * the ordering a player sees is the same on 1.12.2 as anywhere else - including
 * the tie-break, which is what stops two equally-priced items swapping places
 * between page turns.
 *
 * The names are the shop's own vocabulary, so they live beside the sort rather
 * than in a message file: a sort field is a value, not a sentence.
 */
public final class ShopBrowse {
	private ShopBrowse() {
	}

	public enum SortField {
		ADDED_DATE("Ngày thêm"),
		BUY_PRICE("Giá mua"),
		SELL_PRICE("Giá bán"),
		NAME("Tên"),
		ID("ID");

		private final String label;

		SortField(String label) {
			this.label = label;
		}

		/** What the sort button shows. */
		public String label() {
			return label;
		}

		/** The next field in the cycle, for a button that steps through them. */
		public SortField next() {
			SortField[] values = values();

			return values[(ordinal() + 1) % values.length];
		}
	}

	public enum TradeMode {
		BUY,
		SELL
	}

	/**
	 * Sort a page of items.
	 *
	 * `NAME` needs the item's display name, which only a platform can read, so it
	 * arrives as a resolved list of names rather than being looked up here.
	 *
	 * The id is always the final tie-break: without it two items at the same price
	 * order arbitrarily, and the order changes as the underlying map rehashes.
	 */
	public static List<ShopItem> sort(List<ShopItem> items, SortField sortField, boolean ascending, NameLookup names) {
		Comparator<ShopItem> comparator;

		switch (sortField) {
			case BUY_PRICE:
				comparator = new BuyPriceOrder();
				break;
			case SELL_PRICE:
				comparator = new SellPriceOrder();
				break;
			case NAME:
				comparator = new NameOrder(names);
				break;
			case ID:
				comparator = new IdOrder();
				break;
			default:
				comparator = new AddedDateOrder();
				break;
		}

		if (!ascending) {
			comparator = Collections.reverseOrder(comparator);
		}

		List<ShopItem> sorted = new ArrayList<ShopItem>(items);
		final Comparator<ShopItem> primary = comparator;

		Collections.sort(sorted, new Comparator<ShopItem>() {
			@Override
			public int compare(ShopItem first, ShopItem second) {
				int result = primary.compare(first, second);

				return result != 0 ? result : String.CASE_INSENSITIVE_ORDER.compare(first.id(), second.id());
			}
		});

		return sorted;
	}

	/** The display name of an item, which only the platform can read off a stack. */
	public interface NameLookup {
		String nameOf(ShopItem item);
	}

	/** Which category or search a player is looking at, and how it is ordered. */
	public static final class Context {
		private final String category;
		private final String query;
		private final int page;
		private final boolean search;
		private final SortField sortField;
		private final boolean sortAscending;

		public Context(String category, String query, int page, boolean search, SortField sortField, boolean sortAscending) {
			this.category = category;
			this.query = query;
			this.page = Math.max(0, page);
			this.search = search;
			this.sortField = sortField == null ? SortField.ADDED_DATE : sortField;
			this.sortAscending = sortAscending;
		}

		public static Context category(String category, int page) {
			return new Context(category, null, page, false, SortField.ADDED_DATE, false);
		}

		public static Context search(String query, int page) {
			return new Context(null, query, page, true, SortField.ADDED_DATE, false);
		}

		public static Context categorySearch(String category, String query, int page) {
			return new Context(category, query, page, true, SortField.ADDED_DATE, false);
		}

		public String category() {
			return category;
		}

		public String query() {
			return query;
		}

		public int page() {
			return page;
		}

		public boolean search() {
			return search;
		}

		public SortField sortField() {
			return sortField;
		}

		public boolean sortAscending() {
			return sortAscending;
		}

		public Context withPage(int value) {
			return new Context(category, query, value, search, sortField, sortAscending);
		}

		/** A different sort starts at the front; page 3 of the old order means nothing. */
		public Context withSortField(SortField value) {
			return new Context(category, query, 0, search, value, sortAscending);
		}

		public Context toggleSortDirection() {
			return new Context(category, query, 0, search, sortField, !sortAscending);
		}
	}

	/** One item being traded, and where to go back to when it is done. */
	public static final class TradeSession {
		private final String itemId;
		private final TradeMode mode;
		private final int amount;
		private final Context context;

		public TradeSession(String itemId, TradeMode mode, int amount, Context context) {
			this.itemId = itemId;
			this.mode = mode;
			this.amount = Math.max(1, amount);
			this.context = context;
		}

		public String itemId() {
			return itemId;
		}

		public TradeMode mode() {
			return mode;
		}

		public int amount() {
			return amount;
		}

		public Context context() {
			return context;
		}

		public TradeSession withAmount(int value) {
			return new TradeSession(itemId, mode, value, context);
		}

		public TradeSession withMode(TradeMode value) {
			return new TradeSession(itemId, value, amount, context);
		}
	}

	private static final class AddedDateOrder implements Comparator<ShopItem> {
		@Override
		public int compare(ShopItem first, ShopItem second) {
			return Long.compare(first.addedDate(), second.addedDate());
		}
	}

	private static final class BuyPriceOrder implements Comparator<ShopItem> {
		@Override
		public int compare(ShopItem first, ShopItem second) {
			return Double.compare(first.buyPrice(), second.buyPrice());
		}
	}

	private static final class SellPriceOrder implements Comparator<ShopItem> {
		@Override
		public int compare(ShopItem first, ShopItem second) {
			return Double.compare(first.sellPrice(), second.sellPrice());
		}
	}

	private static final class IdOrder implements Comparator<ShopItem> {
		@Override
		public int compare(ShopItem first, ShopItem second) {
			return String.CASE_INSENSITIVE_ORDER.compare(first.id(), second.id());
		}
	}

	private static final class NameOrder implements Comparator<ShopItem> {
		private final NameLookup names;

		private NameOrder(NameLookup names) {
			this.names = names;
		}

		@Override
		public int compare(ShopItem first, ShopItem second) {
			String firstName = names == null ? first.id() : names.nameOf(first);
			String secondName = names == null ? second.id() : names.nameOf(second);

			return String.CASE_INSENSITIVE_ORDER.compare(firstName, secondName);
		}
	}
}
