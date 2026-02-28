package dev.belikhun.luna.core.api.database;

import dev.belikhun.luna.core.api.query.QueryBuilder;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Database {
	Connection connection();

	int update(String sql, List<Object> bindings);

	List<Map<String, Object>> query(String sql, List<Object> bindings);

	default Optional<Map<String, Object>> first(String sql, List<Object> bindings) {
		List<Map<String, Object>> rows = query(sql, bindings);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	default QueryBuilder table(String table) {
		return new QueryBuilder(this, table);
	}

	void close();
}
