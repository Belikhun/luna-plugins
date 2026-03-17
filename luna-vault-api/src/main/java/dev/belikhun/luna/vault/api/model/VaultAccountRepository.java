package dev.belikhun.luna.vault.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.ModelRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class VaultAccountRepository {
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
				.set("player_name", playerName == null ? "" : playerName)
				.set("balance_minor", 0L)
				.set("created_at", now)
				.set("updated_at", now)
				.save();
		});
	}

	public long balance(UUID playerId, String playerName) {
		return findOrCreate(playerId, playerName).getLong("balance_minor", 0L);
	}
}
