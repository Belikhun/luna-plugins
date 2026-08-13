package dev.belikhun.luna.legacy.shop;


import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much of a limited item a player may still trade today.
 *
 * "Today" is a Minecraft day on the overworld, not a wall-clock one, so a server
 * running at a different day length resets in step with what its players
 * actually experience. Usage is deliberately in memory only: it is a per-day
 * throttle, and a restart clearing it is the same as the day turning over.
 */
public final class ShopTradeLimitService {
	private static final long MINECRAFT_DAY_TICKS = 24000L;
	private static final long TICK_MILLIS = 50L;
	private static final long DAY_MILLIS = MINECRAFT_DAY_TICKS * TICK_MILLIS;

	private final ShopGameClock clock;
	private final Map<UUID, Map<String, TradeUsage>> usageByPlayer;
	private long trackedDay;

	public ShopTradeLimitService(ShopGameClock clock) {
		this.clock = clock == null ? ShopGameClock.NONE : clock;
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

	/** @return how many more may be bought, or {@link Integer#MAX_VALUE} when unlimited */
	public int remainingBuy(UUID playerId, ShopItem item) {
		ensureCurrentDay();

		if (!item.hasBuyTradeLimit()) {
			return Integer.MAX_VALUE;
		}

		return Math.max(0, item.buyTradeLimit() - usage(playerId, item.id(), false).bought);
	}

	public int remainingSell(UUID playerId, ShopItem item) {
		ensureCurrentDay();

		if (!item.hasSellTradeLimit()) {
			return Integer.MAX_VALUE;
		}

		return Math.max(0, item.sellTradeLimit() - usage(playerId, item.id(), false).sold);
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

		if (Math.max(0, item.buyTradeLimit() - usage.bought) < amount) {
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

		if (Math.max(0, item.sellTradeLimit() - usage.sold) < amount) {
			return false;
		}

		usage.sold += amount;
		return true;
	}

	public long millisUntilReset() {
		long gameTime = clock.gameTime();

		if (gameTime < 0L) {
			return DAY_MILLIS - Math.floorMod(System.currentTimeMillis(), DAY_MILLIS);
		}

		long dayTime = Math.floorMod(gameTime, MINECRAFT_DAY_TICKS);
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
		long gameTime = clock.gameTime();

		if (gameTime < 0L) {
			return System.currentTimeMillis() / DAY_MILLIS;
		}

		return gameTime / MINECRAFT_DAY_TICKS;
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
