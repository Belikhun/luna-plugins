package dev.belikhun.luna.core.api.model;

import dev.belikhun.luna.core.api.database.Database;
import dev.belikhun.luna.core.api.exception.ModelException;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class Model<T extends Model<T>> {
	private final Database database;
	private final Map<String, Object> attributes;
	private boolean exists;

	protected Model(Database database) {
		this.database = database;
		this.attributes = new LinkedHashMap<>();
		this.exists = false;
	}

	protected abstract String table();

	protected String primaryKey() {
		return "id";
	}

	public T set(String key, Object value) {
		attributes.put(key, value);
		return self();
	}

	public Object get(String key) {
		return attributes.get(key);
	}

	public String getString(String key, String fallback) {
		Object value = attributes.get(key);
		return value == null ? fallback : String.valueOf(value);
	}

	public Long getLong(String key, Long fallback) {
		Object value = attributes.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value == null) {
			return fallback;
		}

		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public Map<String, Object> attributes() {
		return new LinkedHashMap<>(attributes);
	}

	public T fill(Map<String, Object> row) {
		attributes.clear();
		attributes.putAll(row);
		exists = true;
		return self();
	}

	public boolean exists() {
		return exists;
	}

	public void setExists(boolean exists) {
		this.exists = exists;
	}

	public Database database() {
		return database;
	}

	public T save() {
		Object primaryKeyValue = attributes.get(primaryKey());
		if (primaryKeyValue == null) {
			throw new ModelException("Thiếu khóa chính `" + primaryKey() + "` cho model `" + table() + "`.");
		}

		if (exists) {
			database.table(table())
				.where(primaryKey(), primaryKeyValue)
				.update(attributes);
		} else {
			database.table(table()).insert(attributes);
			exists = true;
		}

		return self();
	}

	public boolean delete() {
		if (!exists) {
			return false;
		}

		Object primaryKeyValue = attributes.get(primaryKey());
		if (primaryKeyValue == null) {
			throw new ModelException("Thiếu khóa chính `" + primaryKey() + "` khi xóa model `" + table() + "`.");
		}

		int updated = database.table(table())
			.where(primaryKey(), primaryKeyValue)
			.delete();
		if (updated > 0) {
			exists = false;
		}

		return updated > 0;
	}

	@SuppressWarnings("unchecked")
	private T self() {
		return (T) this;
	}
}

