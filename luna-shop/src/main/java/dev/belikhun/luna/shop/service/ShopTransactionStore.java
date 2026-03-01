package dev.belikhun.luna.shop.service;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.database.NoopDatabase;
import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ShopTransactionStore {
	private static final String TABLE = "shop_transactions";

	private final Database database;
	private final LunaLogger logger;
	private final boolean enabled;

	public ShopTransactionStore(Database database, LunaLogger logger) {
		this.database = database;
		this.logger = logger.scope("Store");
		this.enabled = !(database instanceof NoopDatabase);
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
				List.of(
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
			return List.of();
		}

		int safePage = Math.max(0, page);
		int safePageSize = Math.max(1, pageSize);
		int offset = safePage * safePageSize;

		try {
			List<Map<String, Object>> rows = database.query(
				"SELECT tx_id, player_uuid, player_name, action, item_id, category_id, amount, unit_price, total_price, success, reason, created_at FROM " + TABLE + " WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
				List.of(playerUuid.toString(), safePageSize, offset)
			);

			return rows.stream().map(this::mapRow).toList();
		} catch (Exception exception) {
			logger.warn("Không thể đọc lịch sử giao dịch từ database: " + exception.getMessage());
			return List.of();
		}
	}

	public int countByPlayer(UUID playerUuid) {
		if (!enabled) {
			return 0;
		}

		try {
			Map<String, Object> row = database.first(
				"SELECT COUNT(*) AS total FROM " + TABLE + " WHERE player_uuid = ?",
				List.of(playerUuid.toString())
			).orElse(Map.of("total", 0));

			return asInt(row.get("total"));
		} catch (Exception exception) {
			logger.warn("Không thể đếm lịch sử giao dịch từ database: " + exception.getMessage());
			return 0;
		}
	}

	public Optional<ShopTransactionPlayer> findLatestPlayerByName(String playerName) {
		if (!enabled || playerName == null || playerName.isBlank()) {
			return Optional.empty();
		}

		try {
			Optional<Map<String, Object>> row = database.first(
				"SELECT player_uuid, player_name FROM " + TABLE + " WHERE LOWER(player_name) = LOWER(?) ORDER BY created_at DESC LIMIT 1",
				List.of(playerName.trim())
			);

			if (row.isEmpty()) {
				return Optional.empty();
			}

			String uuidRaw = asString(row.get().get("player_uuid"));
			if (uuidRaw.isBlank()) {
				return Optional.empty();
			}

			return Optional.of(new ShopTransactionPlayer(UUID.fromString(uuidRaw), asString(row.get().get("player_name"))));
		} catch (Exception exception) {
			logger.warn("Không thể tìm người chơi lịch sử từ database: " + exception.getMessage());
			return Optional.empty();
		}
	}

	public List<String> suggestPlayerNames(String input, int limit) {
		if (!enabled) {
			return List.of();
		}

		String needle = input == null ? "" : input.trim();
		int safeLimit = Math.max(1, limit);

		try {
			List<Map<String, Object>> rows = database.query(
				"SELECT player_name, MAX(created_at) AS last_seen FROM " + TABLE + " WHERE LOWER(player_name) LIKE LOWER(?) GROUP BY player_name ORDER BY last_seen DESC LIMIT ?",
				List.of(needle + "%", safeLimit)
			);

			return rows.stream()
				.map(row -> asString(row.get("player_name")))
				.filter(name -> !name.isBlank())
				.toList();
		} catch (Exception exception) {
			logger.warn("Không thể gợi ý tên người chơi từ lịch sử database: " + exception.getMessage());
			return List.of();
		}
	}

	private ShopTransactionEntry mapRow(Map<String, Object> row) {
		return new ShopTransactionEntry(
			asString(row.get("tx_id")),
			asString(row.get("player_uuid")),
			asString(row.get("player_name")),
			asString(row.get("action")),
			asString(row.get("item_id")),
			asString(row.get("category_id")),
			asInt(row.get("amount")),
			asDouble(row.get("unit_price")),
			asDouble(row.get("total_price")),
			asInt(row.get("success")) > 0,
			asString(row.get("reason")),
			asLong(row.get("created_at"))
		);
	}

	private String asString(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private int asInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}

		if (value == null) {
			return 0;
		}

		try {
			return Integer.parseInt(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value == null) {
			return 0L;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private double asDouble(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}

		if (value == null) {
			return 0D;
		}

		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0D;
		}
	}
}
