package dev.belikhun.luna.core.api.profile;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.ModelRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class UserProfileRepository {
	private final Database database;
	private final ModelRepository<UserProfile> repository;

	public UserProfileRepository(Database database) {
		this.database = database;
		this.repository = new ModelRepository<>(database, "user_profiles", "uuid", () -> new UserProfile(database));
	}

	public Optional<UserProfile> findByUuid(UUID uuid) {
		return repository.find(uuid.toString());
	}

	public UserProfile createOrLoad(UUID uuid, String name) {
		return findByUuid(uuid).orElseGet(() -> repository.newModel()
			.set("uuid", uuid.toString())
			.set("name", name)
			.set("last_seen_at", Instant.now().toEpochMilli())
			.set("total_play_seconds", 0L)
			.save());
	}

	public void updateSeen(UserProfile profile, String latestName) {
		profile
			.set("name", latestName)
			.set("last_seen_at", Instant.now().toEpochMilli())
			.save();
	}

	public Database database() {
		return database;
	}
}
