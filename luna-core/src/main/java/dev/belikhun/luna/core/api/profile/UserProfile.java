package dev.belikhun.luna.core.api.profile;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.model.Model;

import java.util.UUID;

public final class UserProfile extends Model<UserProfile> {
	public UserProfile(Database database) {
		super(database);
	}

	@Override
	protected String table() {
		return "user_profiles";
	}

	@Override
	protected String primaryKey() {
		return "uuid";
	}

	public UUID uuid() {
		String value = getString("uuid", "00000000-0000-0000-0000-000000000000");
		return UUID.fromString(value);
	}

	public String name() {
		return getString("name", "");
	}

	public long totalPlaySeconds() {
		Long value = getLong("total_play_seconds", 0L);
		return value == null ? 0L : value;
	}
}
