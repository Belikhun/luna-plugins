package dev.belikhun.luna.legacy.database;

import dev.belikhun.luna.legacy.exception.DatabaseException;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class NoopDatabase implements Database {
	@Override
	public Connection connection() {
		throw new DatabaseException("Database is disabled.");
	}

	@Override
	public int update(String sql, List<Object> bindings) {
		return 0;
	}

	@Override
	public List<Map<String, Object>> query(String sql, List<Object> bindings) {
		return Collections.emptyList();
	}

	@Override
	public void close() {
	}
}

