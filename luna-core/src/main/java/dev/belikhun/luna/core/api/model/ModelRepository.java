package dev.belikhun.luna.core.api.model;

import dev.belikhun.luna.core.api.database.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ModelRepository<T extends Model<T>> {
	private final Database database;
	private final String table;
	private final String primaryKey;
	private final ModelFactory<T> factory;

	public ModelRepository(Database database, String table, String primaryKey, ModelFactory<T> factory) {
		this.database = database;
		this.table = table;
		this.primaryKey = primaryKey;
		this.factory = factory;
	}

	public Optional<T> find(Object id) {
		return database.table(table)
			.where(primaryKey, id)
			.first()
			.map(row -> factory.create().fill(row));
	}

	public List<T> all() {
		List<T> models = new ArrayList<>();
		database.table(table).get().forEach(row -> models.add(factory.create().fill(row)));
		return models;
	}

	public T newModel() {
		return factory.create();
	}
}
