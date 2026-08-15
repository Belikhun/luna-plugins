package dev.belikhun.luna.legacy.shop;

import dev.belikhun.luna.legacy.database.Database;
import dev.belikhun.luna.legacy.database.DatabaseValues;
import dev.belikhun.luna.legacy.database.NoopDatabase;
import dev.belikhun.luna.legacy.logging.LunaLogger;

import dev.belikhun.luna.legacy.string.Strings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ShopTransactionStore {
	private static final String TABLE = "shop_transactions";

	private final Database database;
	private final LunaLogger logger;
	private final boolean enabled;

	/**
	 * @param database where history goes; null or a {@link NoopDatabase} turns it off
	 */
	public ShopTransactionStore(Database database, LunaLogger logger) {
		this.database = database;
		this.logger = logger.scope("Store");

		// null is a platform with no database at all, which 1.12.2 currently is.
		// Without this it counts as enabled, and every insert fails on a null
		// reference whose message is the word "null" - the least useful log line a
		// missing feature can produce
		this.enabled = database != null && !(database instanceof NoopDatabase);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void insert(ShopTransactionEntry entry) {
		if (!enabled) {
			return;
		}

		try {
			database.update(
				"INSERT INTO " + TABLE + " (tx_id, player_uuid, player_name, action, item_id, category_id, amount, unit_price, total_price, success, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				Arrays.asList(
					entry.transactionId(),
					entry.playerUuid(),
					entry.playerName(),
					entry.action(),
					entry.itemId(),
					entry.category(),
					entry.amount(),
					entry.unitPrice(),
					entry.totalPrice(),
					entry.success() ? 1 : 0,
					entry.reason(),
					entry.createdAt()
				)
			);
		} catch (Exception exception) {
			logger.warn("Không thể ghi lịch sử giao dịch vào database: " + exception.getMessage());
		}
	}

	public List<ShopTransactionEntry> findByPlayer(UUID playerUuid, int page, int pageSize) {
		if (!enabled) {
			return Collections.emptyList();
		}

		int safePage = Math.max(0, page);
		int safePageSize = Math.max(1, pageSize);
		int offset = safePage * safePageSize;

		try {
			List<Map<String, Object>> rows = database.query(
				"SELECT tx_id, player_uuid, player_name, action, item_id, category_id, amount, unit_price, total_price, success, reason, created_at FROM " + TABLE + " WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
				Arrays.asList(playerUuid.toString(), safePageSize, offset)
			);

			return rows.stream().map(this::mapRow).collect(Collectors.toList());
		} catch (Exception exception) {
			logger.warn("Không thể đọc lịch sử giao dịch từ database: " + exception.getMessage());
			return Collections.emptyList();
		}
	}

	public int countByPlayer(UUID playerUuid) {
		if (!enabled) {
			return 0;
		}

		try {
			Map<String, Object> row = database.first(
				"SELECT COUNT(*) AS total FROM " + TABLE + " WHERE player_uuid = ?",
				Arrays.asList(playerUuid.toString())
			).orElse(Collections.singletonMap("total", 0));

			return DatabaseValues.intValue(row.get("total"), 0);
		} catch (Exception exception) {
			logger.warn("Không thể đếm lịch sử giao dịch từ database: " + exception.getMessage());
			return 0;
		}
	}

	public Optional<ShopTransactionPlayer> findLatestPlayerByName(String playerName) {
		if (!enabled || Strings.isBlank(playerName)) {
			return Optional.empty();
		}

		try {
			Optional<Map<String, Object>> row = database.first(
				"SELECT player_uuid, player_name FROM " + TABLE + " WHERE LOWER(player_name) = LOWER(?) ORDER BY created_at DESC LIMIT 1",
				Arrays.asList(playerName.trim())
			);

			if (!row.isPresent()) {
				return Optional.empty();
			}

			String uuidRaw = DatabaseValues.string(row.get().get("player_uuid"), "");
			if (Strings.isBlank(uuidRaw)) {
				return Optional.empty();
			}

			return Optional.of(new ShopTransactionPlayer(UUID.fromString(uuidRaw), DatabaseValues.string(row.get().get("player_name"), "")));
		} catch (Exception exception) {
			logger.warn("Không thể tìm người chơi lịch sử từ database: " + exception.getMessage());
			return Optional.empty();
		}
	}

	public List<String> suggestPlayerNames(String input, int limit) {
		if (!enabled) {
			return Collections.emptyList();
		}

		String needle = input == null ? "" : input.trim();
		int safeLimit = Math.max(1, limit);

		try {
			List<Map<String, Object>> rows = database.query(
				"SELECT player_name, MAX(created_at) AS last_seen FROM " + TABLE + " WHERE LOWER(player_name) LIKE LOWER(?) GROUP BY player_name ORDER BY last_seen DESC LIMIT ?",
				Arrays.asList(needle + "%", safeLimit)
			);

			return rows.stream()
				.map(row -> DatabaseValues.string(row.get("player_name"), ""))
				.filter(name -> !Strings.isBlank(name))
				.collect(Collectors.toList());
		} catch (Exception exception) {
			logger.warn("Không thể gợi ý tên người chơi từ lịch sử database: " + exception.getMessage());
			return Collections.emptyList();
		}
	}

	private ShopTransactionEntry mapRow(Map<String, Object> row) {
		return new ShopTransactionEntry(
			DatabaseValues.string(row.get("tx_id"), ""),
			DatabaseValues.string(row.get("player_uuid"), ""),
			DatabaseValues.string(row.get("player_name"), ""),
			DatabaseValues.string(row.get("action"), ""),
			DatabaseValues.string(row.get("item_id"), ""),
			DatabaseValues.string(row.get("category_id"), ""),
			DatabaseValues.intValue(row.get("amount"), 0),
			DatabaseValues.doubleValue(row.get("unit_price"), 0D),
			DatabaseValues.doubleValue(row.get("total_price"), 0D),
			DatabaseValues.intValue(row.get("success"), 0) > 0,
			DatabaseValues.string(row.get("reason"), ""),
			DatabaseValues.longValue(row.get("created_at"), 0L)
		);
	}
}

