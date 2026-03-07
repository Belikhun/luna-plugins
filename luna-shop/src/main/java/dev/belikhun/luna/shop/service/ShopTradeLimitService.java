package dev.belikhun.luna.shop.service;

import dev.belikhun.luna.shop.model.ShopItem;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopTradeLimitService {
	private static final long MINECRAFT_DAY_TICKS = 24000L;
	private static final long TICK_MILLIS = 50L;
	private static final long DAY_MILLIS = MINECRAFT_DAY_TICKS * TICK_MILLIS;

	private final JavaPlugin plugin;
	private final Map<UUID, Map<String, TradeUsage>> usageByPlayer;
	private long trackedDay;

	public ShopTradeLimitService(JavaPlugin plugin) {
		this.plugin = plugin;
		this.usageByPlayer = new ConcurrentHashMap<>();
		this.trackedDay = -1L;
	}

	public int capBuyAmount(UUID playerId, ShopItem item, int requestedAmount) {
		if (requestedAmount <= 0) {
			return 0;
		}

		int remaining = remainingBuy(playerId, item);
		if (remaining == Integer.MAX_VALUE) {
			return requestedAmount;
		}

		return Math.min(requestedAmount, remaining);
	}

	public int capSellAmount(UUID playerId, ShopItem item, int requestedAmount) {
		if (requestedAmount <= 0) {
			return 0;
		}

		int remaining = remainingSell(playerId, item);
		if (remaining == Integer.MAX_VALUE) {
			return requestedAmount;
		}

		return Math.min(requestedAmount, remaining);
	}

	public int remainingBuy(UUID playerId, ShopItem item) {
		ensureCurrentDay();
		if (!item.hasBuyTradeLimit()) {
			return Integer.MAX_VALUE;
		}

		TradeUsage usage = usage(playerId, item.id(), false);
		return Math.max(0, item.buyTradeLimit() - usage.bought);
	}

	public int remainingSell(UUID playerId, ShopItem item) {
		ensureCurrentDay();
		if (!item.hasSellTradeLimit()) {
			return Integer.MAX_VALUE;
		}

		TradeUsage usage = usage(playerId, item.id(), false);
		return Math.max(0, item.sellTradeLimit() - usage.sold);
	}

	public boolean consumeBuy(UUID playerId, ShopItem item, int amount) {
		ensureCurrentDay();
		if (!item.hasBuyTradeLimit()) {
			return true;
		}

		if (amount <= 0) {
			return false;
		}

		TradeUsage usage = usage(playerId, item.id(), true);
		int remaining = Math.max(0, item.buyTradeLimit() - usage.bought);
		if (remaining < amount) {
			return false;
		}

		usage.bought += amount;
		return true;
	}

	public boolean consumeSell(UUID playerId, ShopItem item, int amount) {
		ensureCurrentDay();
		if (!item.hasSellTradeLimit()) {
			return true;
		}

		if (amount <= 0) {
			return false;
		}

		TradeUsage usage = usage(playerId, item.id(), true);
		int remaining = Math.max(0, item.sellTradeLimit() - usage.sold);
		if (remaining < amount) {
			return false;
		}

		usage.sold += amount;
		return true;
	}

	public long millisUntilReset() {
		World world = primaryWorld();
		if (world == null) {
			long now = System.currentTimeMillis();
			long elapsed = Math.floorMod(now, DAY_MILLIS);
			return DAY_MILLIS - elapsed;
		}

		long dayTime = Math.floorMod(world.getFullTime(), MINECRAFT_DAY_TICKS);
		long remainingTicks = MINECRAFT_DAY_TICKS - dayTime;
		if (remainingTicks <= 0L) {
			remainingTicks = MINECRAFT_DAY_TICKS;
		}
		return remainingTicks * TICK_MILLIS;
	}

	private synchronized void ensureCurrentDay() {
		long currentDay = currentDay();
		if (trackedDay == currentDay) {
			return;
		}

		trackedDay = currentDay;
		usageByPlayer.clear();
	}

	private long currentDay() {
		World world = primaryWorld();
		if (world == null) {
			return System.currentTimeMillis() / DAY_MILLIS;
		}

		return world.getFullTime() / MINECRAFT_DAY_TICKS;
	}

	private World primaryWorld() {
		if (plugin.getServer().getWorlds().isEmpty()) {
			return null;
		}

		return plugin.getServer().getWorlds().get(0);
	}

	private TradeUsage usage(UUID playerId, String itemId, boolean create) {
		Map<String, TradeUsage> playerUsage = usageByPlayer.get(playerId);
		if (playerUsage == null) {
			if (!create) {
				return TradeUsage.EMPTY;
			}

			playerUsage = new ConcurrentHashMap<>();
			usageByPlayer.put(playerId, playerUsage);
		}

		TradeUsage usage = playerUsage.get(itemId);
		if (usage == null) {
			if (!create) {
				return TradeUsage.EMPTY;
			}

			usage = new TradeUsage();
			playerUsage.put(itemId, usage);
		}

		return usage;
	}

	private static final class TradeUsage {
		private static final TradeUsage EMPTY = new TradeUsage();
		private int bought;
		private int sold;
	}
}
