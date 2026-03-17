package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.ModelRepository;
import dev.belikhun.luna.vault.api.VaultLeaderboardEntry;
import dev.belikhun.luna.vault.api.VaultLeaderboardPage;
import dev.belikhun.luna.vault.api.VaultPlayerSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VaultAccountRepository {
	public static final int PLAYER_NAME_MAX_LENGTH = 32;

	private final Database database;
	private final ModelRepository<VaultAccountModel> repository;

	public VaultAccountRepository(Database database) {
		this.database = database;
		this.repository = new ModelRepository<>(database, "vault_accounts", "player_uuid", () -> new VaultAccountModel(database));
	}

	public Optional<VaultAccountModel> find(UUID playerId) {
		return repository.find(playerId.toString());
	}

	public Optional<VaultAccountModel> findByName(String playerName) {
		if (playerName == null || playerName.isBlank()) {
			return Optional.empty();
		}

		return database.first(
			"SELECT player_uuid, player_name, balance_minor, created_at, updated_at FROM vault_accounts WHERE LOWER(player_name) = ? LIMIT 1",
			List.of(playerName.trim().toLowerCase(Locale.ROOT))
		).map(row -> new VaultAccountModel(database).fill(row));
	}

	public List<String> searchNamesByPrefix(String partial, int limit) {
		String normalized = partial == null ? "" : partial.trim().toLowerCase(Locale.ROOT);
		int normalizedLimit = Math.max(1, limit);
		List<String> matches = new ArrayList<>();
		database.query(
			"SELECT player_name FROM vault_accounts WHERE LOWER(player_name) LIKE ? ORDER BY updated_at DESC LIMIT ?",
			List.of(normalized + "%", normalizedLimit)
		).forEach(row -> {
			Object rawName = row.get("player_name");
			if (rawName == null) {
				return;
			}

			String name = String.valueOf(rawName);
			if (!name.isBlank()) {
				matches.add(name);
			}
		});
		return matches;
	}

	public VaultAccountModel findOrCreate(UUID playerId, String playerName) {
		return find(playerId).orElseGet(() -> {
			long now = Instant.now().toEpochMilli();
			return repository.newModel()
				.set("player_uuid", playerId.toString())
				.set("player_name", normalizePlayerName(playerName))
				.set("balance_minor", 0L)
				.set("created_at", now)
				.set("updated_at", now)
				.save();
		});
	}

	public Optional<VaultPlayerSnapshot> snapshot(UUID playerId) {
		if (playerId == null) {
			return Optional.empty();
		}

		return find(playerId).map(model -> new VaultPlayerSnapshot(
			playerId,
			normalizePlayerName(model.getString("player_name", "")),
			model.getLong("balance_minor", 0L),
			rankOf(playerId, model.getLong("balance_minor", 0L))
		));
	}

	public int rankOf(UUID playerId, long balanceMinor) {
		if (playerId == null) {
			return 0;
		}

		long aheadCount = database.first(
			"SELECT COUNT(*) AS ahead_count FROM vault_accounts WHERE balance_minor > ? OR (balance_minor = ? AND player_uuid < ?)",
			List.of(balanceMinor, balanceMinor, playerId.toString())
		).map(row -> rawLong(row.get("ahead_count"), 0L)).orElse(0L);
		return (int) aheadCount + 1;
	}

	public VaultLeaderboardPage leaderboard(int page, int pageSize) {
		int normalizedPageSize = Math.max(1, pageSize);
		int requestedPage = Math.max(0, page);
		long totalCount = database.first(
			"SELECT COUNT(*) AS total_count FROM vault_accounts",
			List.of()
		).map(row -> rawLong(row.get("total_count"), 0L)).orElse(0L);
		int maxPage = totalCount <= 0L ? 0 : (int) ((totalCount - 1L) / normalizedPageSize);
		int clampedPage = Math.min(requestedPage, maxPage);
		List<Map<String, Object>> rows = database.query(
			"SELECT player_uuid, player_name, balance_minor FROM vault_accounts ORDER BY balance_minor DESC, player_uuid ASC LIMIT ? OFFSET ?",
			List.of(normalizedPageSize, clampedPage * normalizedPageSize)
		);

		List<VaultLeaderboardEntry> entries = new ArrayList<>();
		for (int index = 0; index < rows.size(); index++) {
			Map<String, Object> row = rows.get(index);
			UUID playerId = rawUuid(row.get("player_uuid"));
			if (playerId == null) {
				continue;
			}

			entries.add(new VaultLeaderboardEntry(
				(clampedPage * normalizedPageSize) + index + 1,
				playerId,
				normalizePlayerName(rawString(row.get("player_name"))),
				rawLong(row.get("balance_minor"), 0L)
			));
		}

		return new VaultLeaderboardPage(entries, clampedPage, normalizedPageSize, maxPage, (int) totalCount);
	}

	public static String normalizePlayerName(String playerName) {
		if (playerName == null) {
			return "";
		}

		String normalized = playerName.trim();
		if (normalized.isBlank()) {
			return "";
		}

		if (normalized.length() <= PLAYER_NAME_MAX_LENGTH) {
			return normalized;
		}

		return normalized.substring(0, PLAYER_NAME_MAX_LENGTH);
	}

	public static String temporaryPlayerName() {
		return "tmp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
	}

	public long balance(UUID playerId, String playerName) {
		return findOrCreate(playerId, playerName).getLong("balance_minor", 0L);
	}

	private long rawLong(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return fallback;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private String rawString(Object value) {
		if (value == null) {
			return "";
		}

		return String.valueOf(value);
	}

	private UUID rawUuid(Object value) {
		String raw = rawString(value);
		if (raw.isBlank()) {
			return null;
		}

		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
